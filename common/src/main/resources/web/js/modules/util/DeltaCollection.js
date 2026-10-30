// A reconnect starts with a snapshot. Revision gaps require a fresh subscription.
export class DeltaCollection {
    constructor(key = 'id') {
        this.key = key;
        this.items = new Map();
        this.epoch = null;
        this.sequence = 0;
        this.meta = {};
    }
    apply(message) {
        if (!message || !Number.isSafeInteger(message.seq)) return false;
        if (message.reset) {
            this.items = new Map((message.items ?? []).map(item => [String(item[this.key]), {...item}]));
        } else {
            if (message.epoch !== this.epoch || message.base !== this.sequence) return false;
            for (const id of message.remove ?? []) this.items.delete(String(id));
            for (const patch of message.upsert ?? []) {
                const id = String(patch[this.key]);
                const item = {...this.items.get(id), ...patch};
                for (const field of patch.$unset ?? []) delete item[field];
                delete item.$unset;
                this.items.set(id, item);
            }
        }
        this.epoch = message.epoch;
        this.sequence = message.seq;
        this.meta = message.meta ?? this.meta;
        return true;
    }
}
