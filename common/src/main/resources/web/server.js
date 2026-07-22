const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const PORT = 3000;
// 目标代理地址
const TARGET_API = 'http://127.0.0.1:8080';

const server = http.createServer((req, res) => {
    // 忽略 query 参数，仅匹配文件路径
    const parsedUrl = new URL(req.url, `http://${req.headers.host}`);
    let pathname = parsedUrl.pathname;

    if (pathname === '/') {
        pathname = '/index.html';
    }

    // 拼接本地路径
    const localFilePath = path.join(__dirname, decodeURIComponent(pathname));

    // 1. 判断本地是否存在对应的静态文件
    fs.stat(localFilePath, (err, stats) => {
        if (!err && stats.isFile()) {
            // 本地存在：直接返回静态文件
            fs.readFile(localFilePath, (readErr, data) => {
                if (readErr) {
                    res.writeHead(500);
                    res.end('Internal Server Error');
                } else {
                    // 简单的 MIME 类型判断（保证 css/js/png 能正常加载）
                    const ext = path.extname(localFilePath).toLowerCase();
                    const mimeTypes = {
                        '.html': 'text/html',
                        '.js': 'text/javascript',
                        '.css': 'text/css',
                        '.json': 'application/json',
                        '.png': 'image/png',
                        '.jpg': 'image/jpeg',
                        '.svg': 'image/svg+xml'
                    };
                    res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
                    res.end(data);
                }
            });
        } else {
            // 2. 本地不存在：代理转发至目标服务器
            console.log(`[代理转发] ${req.method} ${req.url} -> ${TARGET_API}${req.url}`);

            const targetUrl = new URL(req.url, TARGET_API);
            const isHttps = targetUrl.protocol === 'https:';
            const httpModule = isHttps ? https : http;

            const options = {
                hostname: targetUrl.hostname,
                port: targetUrl.port || (isHttps ? 443 : 80),
                path: targetUrl.pathname + targetUrl.search,
                method: req.method,
                headers: {
                    ...req.headers,
                    host: targetUrl.host // 覆盖 host 避免跨域阻拦
                }
            };

            const proxyReq = httpModule.request(options, (proxyRes) => {
                res.writeHead(proxyRes.statusCode, proxyRes.headers);
                proxyRes.pipe(res, { end: true });
            });

            proxyReq.on('error', (proxyErr) => {
                console.error('[代理失败]', proxyErr.message);
                res.writeHead(502);
                res.end('Bad Gateway');
            });

            // 管道转发请求体（如 POST 数据）
            req.pipe(proxyReq, { end: true });
        }
    });
});

// 监听端口，保持进程常驻
server.listen(PORT, () => {
    console.log(`--------------------------------------------------`);
    console.log(`Server 已启动并成功挂起！`);
    console.log(`本地静态文件服务: http://localhost:${PORT}`);
    console.log(`缺失文件请求代理至: ${TARGET_API}`);
    console.log(`--------------------------------------------------`);
});