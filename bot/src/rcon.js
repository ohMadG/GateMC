const net = require('net');

const TYPE_AUTH = 3;
const TYPE_COMMAND = 2;
const TYPE_AUTH_RESPONSE = 2;
const TYPE_RESPONSE = 0;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function packet(id, type, body) {
  const bodyBuf = Buffer.from(`${body}\0\0`, 'utf8');
  const buf = Buffer.alloc(12 + bodyBuf.length);
  buf.writeInt32LE(8 + bodyBuf.length, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  bodyBuf.copy(buf, 12);
  return buf;
}

function command(cmd, timeoutMs = 5000) {
  const host = process.env.RCON_HOST;
  const password = process.env.RCON_PASSWORD;
  if (!host || !password) {
    return Promise.reject(new Error('RCON is not configured'));
  }
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port: Number(process.env.RCON_PORT || 25575) }, () => {
      socket.write(packet(1, TYPE_AUTH, password));
    });
    let authed = false;
    let buffer = Buffer.alloc(0);
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('RCON timed out'));
    }, timeoutMs);
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 4) {
        const size = buffer.readInt32LE(0);
        if (buffer.length < 4 + size) {
          break;
        }
        const id = buffer.readInt32LE(4);
        const type = buffer.readInt32LE(8);
        const body = buffer.toString('utf8', 12, 4 + size - 2);
        buffer = buffer.subarray(4 + size);
        if (!authed) {
          if (id === -1) {
            clearTimeout(timer);
            socket.destroy();
            reject(new Error('RCON auth failed'));
            return;
          }
          if (type === TYPE_AUTH_RESPONSE) {
            authed = true;
            socket.write(packet(2, TYPE_COMMAND, cmd));
          }
        } else if (type === TYPE_RESPONSE) {
          clearTimeout(timer);
          socket.end();
          resolve(body);
          return;
        }
      }
    });
    socket.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

const APPLICATION_NOTICE = JSON.stringify([
  '',
  { text: 'GateMC', color: 'gold', bold: true },
  { text: ' - ', color: 'dark_gray' },
  { text: 'An automatic Discord message about your application has been updated.', color: 'gray' },
]);

async function tellApplicationUpdate(uuid) {
  if (!uuid || !UUID.test(String(uuid))) {
    return false;
  }
  try {
    const reply = await command(`tellraw ${uuid} ${APPLICATION_NOTICE}`);
    return !/no player was found/i.test(reply);
  } catch (e) {
    console.error('[hawkeye-bot] in-game application notice failed:', e.message);
    return false;
  }
}

module.exports = { command, tellApplicationUpdate };
