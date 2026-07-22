import java.io.FileInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.lucko.spark.proto.SparkProtos.PlatformStatistics;
import me.lucko.spark.proto.SparkProtos.RollingAverageValues;
import me.lucko.spark.proto.SparkProtos.WindowStatistics;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerMetadata;
import me.lucko.spark.proto.SparkSamplerProtos.StackTraceNode;
import me.lucko.spark.proto.SparkSamplerProtos.ThreadNode;

/** Minimal offline summary reader for Spark .sparkprofile protobuf files. */
public final class SparkProfileSummary {
    public static void main(final String[] args) throws Exception {
        for (final String arg : args) {
            summarize(Path.of(arg));
        }
    }

    private static void summarize(final Path file) throws Exception {
        final SamplerData data;
        try (FileInputStream input = new FileInputStream(file.toFile())) {
            data = SamplerData.parseFrom(input);
        }
        final SamplerMetadata metadata = data.getMetadata();
        System.out.println("PROFILE\t" + file);
        System.out.printf("META\tstart=%s\tend=%s\tduration_s=%.3f\tinterval_ms=%d\tticks=%d\tmode=%s\tengine=%s\tthreads=%d%n",
            Instant.ofEpochMilli(metadata.getStartTime()),
            Instant.ofEpochMilli(metadata.getEndTime()),
            Duration.ofMillis(metadata.getEndTime() - metadata.getStartTime()).toMillis() / 1000.0D,
            metadata.getInterval(), metadata.getNumberOfTicks(), metadata.getSamplerMode(), metadata.getSamplerEngine(),
            data.getThreadsCount());
        if (metadata.hasPlatformMetadata()) {
            final var platformMeta = metadata.getPlatformMetadata();
            System.out.printf("SERVER\tname=%s\tversion=%s\tminecraft=%s\tbrand=%s\tspark=%d%n",
                platformMeta.getName(), platformMeta.getVersion(), platformMeta.getMinecraftVersion(),
                platformMeta.getBrand(), platformMeta.getSparkVersion());
        }
        metadata.getSourcesMap().entrySet().stream()
            .filter(entry -> entry.getKey().toLowerCase().contains("squaremap") || entry.getKey().toLowerCase().contains("spark"))
            .forEach(entry -> System.out.printf("SOURCE\t%s\tname=%s\tversion=%s%n",
                entry.getKey(), entry.getValue().getName(), entry.getValue().getVersion()));
        if (metadata.hasSystemStatistics()) {
            final var cpu = metadata.getSystemStatistics().getCpu();
            System.out.printf("CPU\tthreads=%d\tmodel=%s\tprocess_1m=%.3f\tsystem_1m=%.3f%n",
                cpu.getThreads(), cpu.getModelName(), cpu.getProcessUsage().getLast1M(), cpu.getSystemUsage().getLast1M());
        }

        if (metadata.hasPlatformStatistics()) {
            final PlatformStatistics platform = metadata.getPlatformStatistics();
            System.out.printf("PLATFORM\tuptime_ms=%d\tplayers=%d", platform.getUptime(), platform.getPlayerCount());
            if (platform.hasTps()) {
                System.out.printf("\ttps_1m=%.3f\ttps_5m=%.3f", platform.getTps().getLast1M(), platform.getTps().getLast5M());
            }
            System.out.println();
            if (platform.hasMspt()) {
                printRolling("MSPT_1M", platform.getMspt().getLast1M());
                printRolling("MSPT_5M", platform.getMspt().getLast5M());
            }
            if (platform.hasMemory()) {
                final var heap = platform.getMemory().getHeap();
                System.out.printf("HEAP\tused=%d\tcommitted=%d\tmax=%d%n", heap.getUsed(), heap.getCommitted(), heap.getMax());
            }
            platform.getGcMap().forEach((name, gc) -> System.out.printf(
                "GC\t%s\ttotal=%d\tavg_time=%.3f\tavg_frequency=%.3f%n",
                name, gc.getTotal(), gc.getAvgTime(), gc.getAvgFrequency()));
        }

        final List<WindowStatistics> windows = new ArrayList<>(data.getTimeWindowStatisticsMap().values());
        windows.sort(Comparator.comparingLong(WindowStatistics::getStartTime));
        if (!windows.isEmpty()) {
            double weightedProcess = 0.0D;
            double weightedSystem = 0.0D;
            double weightedMspt = 0.0D;
            double weightedTps = 0.0D;
            long duration = 0L;
            double maxMspt = 0.0D;
            int ticks = 0;
            for (final WindowStatistics window : windows) {
                final int weight = Math.max(1, window.getDuration());
                duration += weight;
                weightedProcess += window.getCpuProcess() * weight;
                weightedSystem += window.getCpuSystem() * weight;
                weightedMspt += window.getMsptMedian() * weight;
                weightedTps += window.getTps() * weight;
                maxMspt = Math.max(maxMspt, window.getMsptMax());
                ticks += window.getTicks();
                System.out.printf("WINDOW\tduration=%d\tticks=%d\ttps=%.3f\tmspt_median=%.3f\tmspt_max=%.3f\tcpu_process=%.3f\tcpu_system=%.3f%n",
                    window.getDuration(), window.getTicks(), window.getTps(), window.getMsptMedian(), window.getMsptMax(),
                    window.getCpuProcess(), window.getCpuSystem());
            }
            System.out.printf("WINDOWS\tcount=%d\tduration_units=%d\tticks=%d\tcpu_process=%.3f\tcpu_system=%.3f\ttps=%.3f\tmspt_median=%.3f\tmspt_max=%.3f%n",
                windows.size(), duration, ticks, weightedProcess / duration, weightedSystem / duration,
                weightedTps / duration, weightedMspt / duration, maxMspt);
        }

        for (final ThreadNode thread : data.getThreadsList()) {
            final double total = sum(thread.getTimesList());
            System.out.printf("THREAD\t%s\ttotal=%.3f\tnodes=%d\troots=%d%n",
                thread.getName(), total, thread.getChildrenCount(), thread.getChildrenRefsCount());
            final Map<String, Double> inclusive = new HashMap<>();
            final Map<String, Double> self = new HashMap<>();
            final List<StackTraceNode> nodes = thread.getChildrenList();
            final Set<Integer> roots = new HashSet<>(thread.getChildrenRefsList());
            for (final int root : roots) {
                collect(root, nodes, inclusive, self, new HashSet<>());
            }
            printHotspots("HOT", total, self, name -> true, 12);
            printHotspots("SQUAREMAP", total, inclusive, name -> name.startsWith("xyz.jpenilla.squaremap"), 15);
            printHotspots("IMAGE", total, inclusive, name -> name.contains("imageio") || name.contains("ImageIO")
                || name.contains("TileStorage") || name.contains("WebP") || name.contains("libwebp"), 12);
        }
        System.out.println();
    }

    private static double collect(
        final int index,
        final List<StackTraceNode> nodes,
        final Map<String, Double> inclusive,
        final Map<String, Double> self,
        final Set<Integer> path
    ) {
        if (index < 0 || index >= nodes.size() || !path.add(index)) {
            return 0.0D;
        }
        final StackTraceNode node = nodes.get(index);
        final String name = node.getClassName() + '#' + node.getMethodName();
        final double value = sum(node.getTimesList());
        inclusive.merge(name, value, Double::sum);
        double children = 0.0D;
        for (final int child : node.getChildrenRefsList()) {
            children += collect(child, nodes, inclusive, self, new HashSet<>(path));
        }
        self.merge(name, Math.max(0.0D, value - children), Double::sum);
        return value;
    }

    private static void printHotspots(
        final String label,
        final double threadTotal,
        final Map<String, Double> values,
        final java.util.function.Predicate<String> filter,
        final int limit
    ) {
        values.entrySet().stream()
            .filter(entry -> filter.test(entry.getKey()) && entry.getValue() > 0.0D)
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit)
            .forEach(entry -> System.out.printf("%s\t%.3f\t%.3f%%\t%s%n", label, entry.getValue(),
                threadTotal == 0.0D ? 0.0D : entry.getValue() * 100.0D / threadTotal, entry.getKey()));
    }

    private static void printRolling(final String label, final RollingAverageValues values) {
        System.out.printf("%s\tmean=%.3f\tmedian=%.3f\tp95=%.3f\tmin=%.3f\tmax=%.3f%n",
            label, values.getMean(), values.getMedian(), values.getPercentile95(), values.getMin(), values.getMax());
    }

    private static double sum(final List<Double> values) {
        double result = 0.0D;
        for (final double value : values) {
            result += value;
        }
        return result;
    }
}
