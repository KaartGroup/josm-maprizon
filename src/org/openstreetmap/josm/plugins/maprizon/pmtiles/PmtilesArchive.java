// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.pmtiles;

import org.openstreetmap.josm.tools.Logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 * One remote PMTiles archive, addressed directly over HTTP range requests.
 *
 * <p><b>Why this replaces {@code ch.poole.geo.pmtiles.Reader}.</b> The bundled
 * reader computes a tile's id with {@code Hilbert.zxyToIndex}, which truncates its
 * accumulator to {@code int} on every iteration (still true in upstream 0.3.7).
 * Above {@code 2^31 - 1} the id wraps negative, and z16 holds {@code 4^16 == 2^32}
 * tiles — so from z16 down, {@code Reader.getTile} looks up an id that cannot
 * exist and returns null. Measured on the live {@code public_imagery-right}
 * archive: ~700 z16 tiles are baked and NONE were reachable, which is why the
 * plugin silently rendered z15 as its finest detail and tracks looked jagged at
 * full zoom. See {@link TileId#zxyToIndex(int, long, long)}.
 *
 * <p>Because the reader's directory handling is package-private there is no way to
 * correct the id from outside it, so this class does the three things the reader
 * did: read the header, hold the tile index, and range-read a tile body. Tile
 * decoding (gzip + MVT) stays in {@code PmtilesTileLoader}.
 *
 * <p><b>Two things this buys beyond fixing z16.</b> Tile existence becomes a LOCAL
 * lookup — {@link #hasTile(int, long, long)} answers with zero HTTP, so the
 * fetch path no longer has to probe-and-miss to discover what is there. And range
 * reads go through {@link #readRange}, which sees the real HTTP status code, so an
 * expired presigned URL is detected as a genuine 403 instead of by matching "403"
 * inside an exception message.
 *
 * <p>Not thread-safe on {@link #open()}; callers serialize construction (the tile
 * loader does this under its own monitor). Reads after opening are safe.
 */
public final class PmtilesArchive {

    /** Hard cap on a single tile body, so a corrupt directory entry cannot make us
     * allocate wildly. Real tiles here are single-digit KB. */
    private static final int MAX_TILE_BYTES = 16 * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final String url;
    private PmtilesDirectory.Header header;
    /** Entry starts, ascending — PMTiles v3 directories are sorted by tile id, and
     * {@link #open()} asserts it rather than assuming it. */
    private long[] starts;
    private long[] runs;
    private long[] offsets;
    private long[] lengths;

    public PmtilesArchive(String url) {
        this.url = url;
    }

    /** Fetch + parse the header and the root directory. One range read each. */
    public void open() throws IOException {
        byte[] head = readRange(0, PmtilesDirectory.HEADER_BYTES - 1);
        PmtilesDirectory.Header h = PmtilesDirectory.parseHeader(head);
        if (h.specVersion != 3) {
            throw new IOException("unsupported PMTiles spec version " + h.specVersion);
        }
        byte[] raw = readRange(h.rootOffset, h.rootOffset + h.rootLength - 1);
        byte[] dir = h.internalCompression == PmtilesDirectory.COMPRESSION_GZIP
                ? PmtilesTileLoader.gunzip(raw)
                : raw;
        List<PmtilesDirectory.Entry> entries = PmtilesDirectory.decodeEntries(dir);

        int n = entries.size();
        starts = new long[n];
        runs = new long[n];
        offsets = new long[n];
        lengths = new long[n];
        boolean sorted = true;
        for (int i = 0; i < n; i++) {
            PmtilesDirectory.Entry e = entries.get(i);
            starts[i] = e.tileId;
            runs[i] = e.runLength;
            offsets[i] = e.offset;
            lengths[i] = e.length;
            if (i > 0 && starts[i] < starts[i - 1]) {
                sorted = false;
            }
        }
        if (!sorted) {
            // Binary search below depends on this. Rather than silently return
            // wrong tiles, refuse the archive — the caller falls back to "no data"
            // for this facing, which is visible, instead of subtly wrong geometry.
            throw new IOException("PMTiles directory is not sorted by tile id");
        }
        this.header = h;

        if (!h.isFullyInRoot()) {
            // Leaf directories exist, so this index is PARTIAL. Every current
            // Maprizon archive has leaf_dirs_length == 0, so this is a
            // future-proofing signal rather than a live case; log loudly because
            // the symptom would be "some tiles mysteriously missing".
            Logging.warn("Maprizon: " + url + " uses leaf directories ("
                    + h.leafDirsLength + " bytes) — tile index is incomplete, "
                    + "deep tiles may be reported absent");
        }
    }

    public boolean isOpen() {
        return header != null;
    }

    public PmtilesDirectory.Header header() {
        return header;
    }

    public int minZoom() {
        return header.minZoom;
    }

    public int maxZoom() {
        return header.maxZoom;
    }

    /** Number of tile-id slots the directory covers (runs expanded). */
    public long tileCount() {
        long total = 0;
        for (long r : runs) {
            if (r > 0) {
                total += r;
            }
        }
        return total;
    }

    /**
     * Does this archive hold a tile at {@code (z,x,y)}? Pure local lookup, no I/O.
     *
     * <p>This is the capability the bundled reader never exposed, and it is what
     * removes the need to fetch-and-see. A caller can decide whether to descend,
     * ascend, or skip entirely without spending a request.
     */
    public boolean hasTile(int z, long x, long y) {
        return indexOf(TileId.toTileId(z, x, y)) >= 0;
    }

    /**
     * Raw (still-compressed) tile body, or {@code null} when the archive holds no
     * tile at this address.
     *
     * <p>Returning null for "absent" preserves the contract
     * {@code PmtilesTileLoader} already relies on to tell "nothing baked here" from
     * "present but empty".
     */
    public byte[] getTile(int z, long x, long y) throws IOException {
        int i = indexOf(TileId.toTileId(z, x, y));
        if (i < 0) {
            return null;
        }
        long len = lengths[i];
        if (len <= 0) {
            return null;
        }
        if (len > MAX_TILE_BYTES) {
            throw new IOException("implausible tile length " + len + " at z" + z
                    + "/" + x + "/" + y);
        }
        long from = header.tileDataOffset + offsets[i];
        return readRange(from, from + len - 1);
    }

    /** Index of the entry whose run covers {@code tileId}, or -1. */
    private int indexOf(long tileId) {
        if (starts == null) {
            return -1;
        }
        int i = Arrays.binarySearch(starts, tileId);
        if (i >= 0) {
            return runs[i] > 0 ? i : -1;
        }
        // Not an exact start: the only candidate is the entry just before the
        // insertion point, whose run may extend over this id (identical tiles are
        // deduplicated into a single entry spanning several ids).
        int cand = -i - 2;
        if (cand < 0) {
            return -1;
        }
        long start = starts[cand];
        long run = runs[cand];
        return (run > 0 && tileId >= start && tileId < start + run) ? cand : -1;
    }

    /**
     * HTTP range read.
     *
     * <p>Deliberately checks {@code getResponseCode()} rather than letting
     * {@code getInputStream()} throw: the status is the difference between "this
     * presigned URL expired" (403 → re-sign) and "no such object" (404 → absent),
     * and recovering it from an exception message was previously a substring match.
     */
    byte[] readRange(long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("Range", "bytes=" + from + "-" + to);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new PmtilesForbiddenException("403 for " + redact(url));
            }
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + redact(url));
            }
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Strip the query string before logging: for org archives it carries the
     * presigning signature, which does not belong in JOSM's log. */
    private static String redact(String u) {
        int q = u.indexOf('?');
        return q < 0 ? u : u.substring(0, q) + "?<signature-redacted>";
    }

    /** Thrown for a 403, so the tile loader can re-sign on a real status code
     * instead of pattern-matching an exception message. */
    public static final class PmtilesForbiddenException extends IOException {
        private static final long serialVersionUID = 1L;

        PmtilesForbiddenException(String msg) {
            super(msg);
        }
    }
}
