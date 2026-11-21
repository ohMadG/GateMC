const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const multer = require('multer');
const antivirus = require('./antivirus');

// file-type is ESM-only from v22 (the fix for a parser DoS in older versions) - load it lazily
// via dynamic import from this CommonJS module; Node caches the import after the first call.
const fileTypePromise = import('file-type');

const UPLOAD_ROOT = process.env.UPLOAD_DIR || path.join(__dirname, '..', 'uploads');
const TMP_DIR = path.join(UPLOAD_ROOT, 'tmp');
const FINAL_DIR = path.join(UPLOAD_ROOT, 'screenshots');
fs.mkdirSync(TMP_DIR, { recursive: true });
fs.mkdirSync(FINAL_DIR, { recursive: true });

const MAX_FILES = 3;
const MAX_FILE_BYTES = 8 * 1024 * 1024;

// Both the extension we use for the stored file and what file-type must independently confirm
// from the file's actual bytes - the client-supplied mimetype/extension are never trusted alone.
const ALLOWED_MIME_EXT = {
    'image/png': 'png',
    'image/jpeg': 'jpg',
    'image/webp': 'webp',
    'image/gif': 'gif',
};

const storage = multer.diskStorage({
    destination: TMP_DIR,
    filename: (req, file, cb) => cb(null, crypto.randomBytes(16).toString('hex') + '.tmp'),
});

const upload = multer({
    storage,
    limits: { fileSize: MAX_FILE_BYTES, files: MAX_FILES },
    fileFilter: (req, file, cb) => {
        if (Object.prototype.hasOwnProperty.call(ALLOWED_MIME_EXT, file.mimetype)) {
            return cb(null, true);
        }
        cb(new Error('UNSUPPORTED_TYPE'));
    },
});

function uploadErrorMessage(err) {
    if (!err) {
        return null;
    }
    if (err.code === 'LIMIT_FILE_SIZE') {
        return `Each screenshot must be ${MAX_FILE_BYTES / (1024 * 1024)}MB or smaller.`;
    }
    if (err.code === 'LIMIT_FILE_COUNT' || err.code === 'LIMIT_UNEXPECTED_FILE') {
        return `You can attach up to ${MAX_FILES} screenshots.`;
    }
    if (err.message === 'UNSUPPORTED_TYPE') {
        return 'Screenshots must be PNG, JPEG, WEBP, or GIF images.';
    }
    return 'There was a problem with your uploaded files.';
}

function cleanupTemp(files) {
    (files || []).forEach((f) => fs.unlink(f.path, () => {}));
}

/**
 * Verifies each file's real content (magic bytes, not the client-supplied mimetype) and scans it
 * with ClamAV before moving it into permanent storage under a fresh random name. Throws (and
 * cleans up everything it touched, including files already moved) on the first problem found -
 * fails closed if the virus scanner itself can't be reached.
 */
async function verifyAndStore(files) {
    const stored = [];
    try {
        for (const file of files) {
            const { fileTypeFromFile } = await fileTypePromise;
            const type = await fileTypeFromFile(file.path);
            if (!type || !Object.prototype.hasOwnProperty.call(ALLOWED_MIME_EXT, type.mime)) {
                throw new Error('That file is not a recognized image.');
            }
            const scan = await antivirus.scanFile(file.path);
            if (scan.isInfected) {
                throw new Error('One of your files was flagged by the virus scanner and was not accepted.');
            }
            const finalName = crypto.randomBytes(16).toString('hex') + '.' + ALLOWED_MIME_EXT[type.mime];
            fs.renameSync(file.path, path.join(FINAL_DIR, finalName));
            stored.push(finalName);
        }
        return stored;
    } catch (err) {
        cleanupTemp(files);
        stored.forEach((name) => fs.unlink(path.join(FINAL_DIR, name), () => {}));
        throw err;
    }
}

const SAFE_FILENAME = /^[0-9a-f]{32}\.(png|jpg|webp|gif)$/;

function screenshotPath(filename) {
    if (!SAFE_FILENAME.test(filename)) {
        return null;
    }
    return path.join(FINAL_DIR, filename);
}

module.exports = { upload, uploadErrorMessage, cleanupTemp, verifyAndStore, screenshotPath, MAX_FILES, ready: fileTypePromise };
