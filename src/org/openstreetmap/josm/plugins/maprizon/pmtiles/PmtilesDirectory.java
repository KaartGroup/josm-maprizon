// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.pmtiles;

import ch.poole.geo.pmtiles.VarInt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal PMTiles v3 header + root-directory decoder: answers "which tiles exist
 * in this archive" WITHOUT fetching or decoding a single tile.
 *
 * <p><b>Why this is worth having.</b> Measured against the live PUBLIC archives on
 * 2026-07-29: each per-facing archive holds ~1300 tiles across z3–z16, its root
 * directory is ~3.2 KB, and {@code leaf_dirs_length} is <b>0</b> — so that entire
 * tile index arrives in one small range read, needing no tile decoding, which is
 * what makes a pre-download coverage display cheap enough to run on layer add.
 *
 * <p><b>Do not generalize that measurement.</b> It was taken on the smallest
 * archives the plugin reads and was then treated as true of all of them; an
 * organization's full bake is far larger and keeps most of its index in LEAF
 * DIRECTORIES. {@code PmtilesArchive} follows those; the helpers here that stop at
 * the root say so individually.
 *
 * <p>The bundled reader cannot do this for us: {@code Reader$Directory},
 * {@code Reader.getZoomOffset} and {@code Util.decompress} are all
 * package-private, and {@code Reader} exposes no way to enumerate entries. We do
 * reuse the library's {@link VarInt} so the varint decoding is not a second
 * implementation.
 *
 * <p>It also supplies the byte offsets needed to FETCH a tile directly, which is
 * what lets {@code PmtilesArchive} address tiles without the bundled
 * {@code Reader} — see {@link TileId#zxyToIndex(int, long, long)} for the
 * library defect that makes that necessary.
 *
 * <p>Everything here is PURE — bytes in, values out, no network. The fetching
 * lives in {@code PmtilesArchive}. Keeping the two apart is what makes this class
 * testable against a recorded archive.
 *
 * <p>Spec: PMTiles v3, github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
public final class PmtilesDirectory {

    /** The v3 header is a fixed 127 bytes at offset 0. */
    public static final int HEADER_BYTES = 127;

    /** {@code internal_compression} / {@code tile_compression} value for gzip. */
    public static final byte COMPRESSION_GZIP = 2;
    /** ...and for "no compression". */
    public static final byte COMPRESSION_NONE = 1;

    private PmtilesDirectory() {
    }

    /** The header fields this class needs, plus the ones worth logging. */
    public static final class Header {
        public final int specVersion;
        public final long rootOffset;
        public final long rootLength;
        public final long leafDirsOffset;
        public final long leafDirsLength;
        /** Absolute offset of the tile-data section; entry offsets are relative
         * to this. */
        public final long tileDataOffset;
        /** Compression of the DIRECTORY bytes (not the tiles). */
        public final byte internalCompression;
        /** Compression of TILE bodies. */
        public final byte tileCompression;
        public final int minZoom;
        public final int maxZoom;
        /** Entry count the header itself claims — an independent check on a decode. */
        public final long tileEntries;

        Header(int specVersion, long rootOffset, long rootLength, long leafDirsOffset,
               long leafDirsLength, long tileDataOffset, byte internalCompression,
               byte tileCompression, int minZoom, int maxZoom, long tileEntries) {
            this.specVersion = specVersion;
            this.rootOffset = rootOffset;
            this.rootLength = rootLength;
            this.leafDirsOffset = leafDirsOffset;
            this.leafDirsLength = leafDirsLength;
            this.tileDataOffset = tileDataOffset;
            this.internalCompression = internalCompression;
            this.tileCompression = tileCompression;
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.tileEntries = tileEntries;
        }

        /** True when the whole index is in the root directory, so one range read is
         * sufficient. The public per-facing bakes satisfy this; an organization's
         * bake does NOT — it is mostly leaf directories, and a caller that stops at
         * the root sees almost none of it. */
        public boolean isFullyInRoot() {
            return leafDirsLength == 0;
        }
    }

    /**
     * One directory entry.
     *
     * <p>{@code runLength} 0 means the entry points at a LEAF DIRECTORY rather
     * than tile data; {@code runLength > 1} means that many consecutive tile ids
     * share one deduplicated tile body (identical tiles are stored once). For
     * "does a tile exist here", every id in the run counts.
     */
    public static final class Entry {
        public final long tileId;
        public final long runLength;
        /** Byte offset of the tile body, relative to the archive's
         * {@code tile_data_offset}. */
        public final long offset;
        /** Byte length of the tile body. */
        public final long length;

        Entry(long tileId, long runLength, long offset, long length) {
            this.tileId = tileId;
            this.runLength = runLength;
            this.offset = offset;
            this.length = length;
        }

        /** True when {@code id} falls in this entry's run. */
        public boolean covers(long id) {
            return runLength > 0 && id >= tileId && id < tileId + runLength;
        }
    }

    /**
     * Parse the fixed header.
     *
     * @param head at least {@link #HEADER_BYTES} bytes read from offset 0
     * @throws IOException if the magic is wrong or the buffer is short — callers
     *         should treat this as "no availability data", never as a fatal error
     */
    public static Header parseHeader(byte[] head) throws IOException {
        if (head == null || head.length < HEADER_BYTES) {
            throw new IOException("PMTiles header too short: "
                    + (head == null ? "null" : head.length + " bytes"));
        }
        if (head[0] != 'P' || head[1] != 'M' || head[2] != 'T' || head[3] != 'i'
                || head[4] != 'l' || head[5] != 'e' || head[6] != 's') {
            throw new IOException("not a PMTiles archive (bad magic)");
        }
        int specVersion = head[7] & 0xFF;
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        long rootOffset = b.getLong(8);
        long rootLength = b.getLong(16);
        long leafOffset = b.getLong(40);
        long leafLength = b.getLong(48);
        long tileDataOffset = b.getLong(56);
        long tileEntries = b.getLong(80);
        byte internalCompression = head[97];
        byte tileCompression = head[98];
        int minZoom = head[100] & 0xFF;
        int maxZoom = head[101] & 0xFF;
        return new Header(specVersion, rootOffset, rootLength, leafOffset, leafLength,
                tileDataOffset, internalCompression, tileCompression,
                minZoom, maxZoom, tileEntries);
    }

    /**
     * Decode a directory's entries from its DECOMPRESSED bytes.
     *
     * <p>v3 serializes a directory as five sections, each a run of varints: the
     * entry count, then tile-id deltas, then run lengths, then lengths, then
     * offsets. The sections are positional, so every one must be consumed in full
     * or the following section is read from the wrong bytes.
     */
    public static List<Entry> decodeEntries(byte[] decompressed) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(decompressed).order(ByteOrder.LITTLE_ENDIAN);
        long count;
        try {
            count = VarInt.getVarLong(buf);
        } catch (RuntimeException e) {
            throw new IOException("truncated PMTiles directory (entry count): " + e);
        }
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IOException("implausible PMTiles directory entry count: " + count);
        }
        int n = (int) count;

        long[] tileIds = new long[n];
        long[] runLengths = new long[n];
        long[] lengths = new long[n];
        long[] offsets = new long[n];
        try {
            // tile ids are DELTA-coded; the first is absolute.
            long prev = 0;
            for (int i = 0; i < n; i++) {
                prev += VarInt.getVarLong(buf);
                tileIds[i] = prev;
            }
            for (int i = 0; i < n; i++) {
                runLengths[i] = VarInt.getVarLong(buf);
            }
            for (int i = 0; i < n; i++) {
                lengths[i] = VarInt.getVarLong(buf);
            }
            // Offsets use a run-on encoding: a stored 0 means "immediately after
            // the previous entry's body", anything else is the offset plus one.
            // Decoding this wrong yields plausible-looking offsets that read the
            // wrong bytes, so it is not safe to approximate.
            for (int i = 0; i < n; i++) {
                long v = VarInt.getVarLong(buf);
                if (v == 0 && i > 0) {
                    offsets[i] = offsets[i - 1] + lengths[i - 1];
                } else {
                    offsets[i] = v - 1;
                }
            }
        } catch (RuntimeException e) {
            // getVarLong throws BufferUnderflowException on a short buffer.
            throw new IOException("truncated PMTiles directory after "
                    + n + " declared entries: " + e);
        }

        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Entry(tileIds[i], runLengths[i], offsets[i], lengths[i]));
        }
        return out;
    }

    /**
     * Expand entries into the set of tile ids that actually hold data.
     *
     * <p>Leaf-directory entries ({@code runLength == 0}) are skipped: they address
     * more directory, not tiles, and following them would need a second fetch. So
     * on an archive with leaves this returns a SUBSET — everything the root happens
     * to address directly. That is fine for the diagnostics this serves and wrong
     * for anything that must answer "does this tile exist"; use
     * {@code PmtilesArchive.hasTile}, which walks into the leaves.
     * {@link Header#isFullyInRoot()} tells a caller which situation it is in.
     *
     * @param maxIds hard cap so a malformed or unexpectedly huge run cannot
     *               exhaust memory; the count is bounded in practice by the
     *               header's {@code tileEntries}, but this is untrusted input
     *               parsed from the network
     */
    public static long[] expandDataTileIds(List<Entry> entries, int maxIds) {
        long[] ids = new long[Math.min(maxIds, 1 << 20)];
        int k = 0;
        for (Entry e : entries) {
            if (e.runLength <= 0) {
                continue; // leaf directory pointer, not tile data
            }
            for (long r = 0; r < e.runLength && k < ids.length; r++) {
                ids[k++] = e.tileId + r;
            }
            if (k >= ids.length) {
                break;
            }
        }
        long[] trimmed = new long[k];
        System.arraycopy(ids, 0, trimmed, 0, k);
        return trimmed;
    }
}
