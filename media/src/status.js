const net = require('net');

const HOST = process.env.MC_STATUS_HOST || '45.85.60.126';
const PORT = Number(process.env.MC_STATUS_PORT || 25565);
const TTL = 30 * 1000;

let cache = null;
let pending = null;

function varint(n) {
    const out = [];
    do {
        let b = n & 0x7f;
        n >>>= 7;
        if (n) {
            b |= 0x80;
        }
        out.push(b);
    } while (n);
    return Buffer.from(out);
}

function packet(id, data) {
    const body = Buffer.concat([varint(id), data]);
    return Buffer.concat([varint(body.length), body]);
}

function readVarint(buf, at) {
    let n = 0;
    let shift = 0;
    for (let i = at; i < buf.length && i < at + 5; i++) {
        n |= (buf[i] & 0x7f) << shift;
        if (!(buf[i] & 0x80)) {
            return { value: n >>> 0, next: i + 1 };
        }
        shift += 7;
    }
    return null;
}

function ping() {
    return new Promise((resolve, reject) => {
        const sock = net.connect(PORT, HOST);
        let buf = Buffer.alloc(0);
        const fail = (e) => {
            sock.destroy();
            reject(e instanceof Error ? e : new Error(String(e)));
        };
        sock.setTimeout(4000, () => fail('timeout'));
        sock.on('error', fail);
        sock.on('connect', () => {
            const host = Buffer.from(HOST);
            sock.write(packet(0, Buffer.concat([varint(767), varint(host.length), host, Buffer.from([PORT >> 8, PORT & 255]), varint(1)])));
            sock.write(packet(0, Buffer.alloc(0)));
        });
        sock.on('data', (d) => {
            buf = Buffer.concat([buf, d]);
            if (buf.length > 65536) {
                return fail('too large');
            }
            const len = readVarint(buf, 0);
            if (!len || buf.length < len.next + len.value) {
                return;
            }
            const id = readVarint(buf, len.next);
            const str = id && readVarint(buf, id.next);
            if (!str) {
                return fail('bad packet');
            }
            try {
                const json = JSON.parse(buf.slice(str.next, str.next + str.value).toString('utf8'));
                sock.destroy();
                resolve({ online: Number(json.players?.online) || 0, max: Number(json.players?.max) || 0 });
            } catch (e) {
                fail(e);
            }
        });
    });
}

async function players() {
    if (cache && Date.now() - cache.at < TTL) {
        return cache;
    }
    if (!pending) {
        pending = ping()
            .then((r) => (cache = { ...r, up: true, at: Date.now() }))
            .catch(() => (cache = { online: 0, max: 0, up: false, at: Date.now() }))
            .finally(() => (pending = null));
    }
    return pending;
}

module.exports = { players };
