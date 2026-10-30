export function compressionQuery() {
    return typeof DecompressionStream === 'function' ? '&compression=gzip' : '';
}

// Decompression is asynchronous; one queue per connection preserves revision order across topics.
export function listen(source, event, handler, current, failed) {
    source.addEventListener(event, message => {
        source.messageQueue = (source.messageQueue ?? Promise.resolve()).then(async () => {
            if (!current()) return;
            let value = JSON.parse(message.data);
            if (typeof value.gzip === 'string') {
                const bytes = Uint8Array.from(atob(value.gzip), character => character.charCodeAt(0));
                value = await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))).json();
            }
            if (current()) handler(value);
        }).catch(() => { if (current()) failed(); });
    });
}
