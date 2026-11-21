const NodeClam = require('clamscan');

const clamscanPromise = new NodeClam()
    .init({
        removeInfected: false,
        quarantineInfected: false,
        debugMode: false,
        clamdscan: {
            host: process.env.CLAMAV_HOST || 'gatemc-clamav',
            port: Number(process.env.CLAMAV_PORT || 3310),
            timeout: 60000,
            localFallback: false,
        },
        preference: 'clamdscan',
    })
    .catch((e) => {
        console.error('ClamAV init failed:', e.message);
        return null;
    });

/** Scans a file on disk. Throws if the scanner itself is unreachable - callers must fail closed. */
async function scanFile(filePath) {
    const clamscan = await clamscanPromise;
    if (!clamscan) {
        throw new Error('Virus scanner unavailable');
    }
    const { isInfected, viruses } = await clamscan.isInfected(filePath);
    return { isInfected, viruses };
}

/** Checks clamd is actually reachable, for health checks - throws if not. */
async function ping() {
    const clamscan = await clamscanPromise;
    if (!clamscan) {
        throw new Error('Virus scanner unavailable');
    }
    return clamscan.getVersion();
}

module.exports = { scanFile, ping };
