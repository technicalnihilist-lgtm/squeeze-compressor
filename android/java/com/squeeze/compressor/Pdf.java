package com.squeeze.compressor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A real PDF rewriter — no third-party library, no Android imports, so it can be
 * exercised on a plain JVM.
 *
 * It parses the file into objects, walks the object graph from the catalog, and
 * writes a brand-new PDF containing only what is reachable:
 *
 *  - <b>FlateDecode streams are re-deflated at level 9.</b> The inflated bytes are
 *    identical, so the rendering cannot change; only the compression effort does.
 *    A stream that does not shrink keeps its original bytes.
 *  - <b>Unfiltered streams are deflated</b> and given a /FlateDecode filter when that
 *    wins — most hand-made and many print-driver PDFs carry raw content streams.
 *  - <b>Unreachable objects are dropped</b> (incremental-update leftovers, orphaned
 *    images, replaced page trees).
 *  - <b>Metadata is stripped</b>: /Info, the catalog's /Metadata XMP packet and
 *    /PieceInfo are removed, so author, producer, creation date and XMP do not
 *    survive the rewrite.
 *  - <b>Images are never re-encoded.</b> A DCTDecode/JPXDecode/CCITTFax stream is
 *    copied byte for byte, because re-encoding it would change what the page looks
 *    like. Only DEFLATE is touched, and DEFLATE is lossless.
 *
 * The output uses a classic cross-reference table, or a cross-reference stream when
 * the document keeps object streams (those cannot be described by a classic table).
 * If the rewrite does not come out smaller, the caller keeps the original file.
 */
final class Pdf {

    /** Streams bigger than this are copied verbatim: not worth the heap to re-deflate. */
    private static final int MAX_REFILTER = 24 << 20;
    /** Abort an inflation that expands past this — a decompression bomb is not a bug report. */
    private static final int MAX_INFLATE = 96 << 20;

    private Pdf() { }

    static final class Report {
        long in, out;
        int objectsFound, objectsKept, objectsDropped;
        int streamsReflated, streamsCopied;
        boolean encrypted;
        boolean objectStreams;
        String note;
    }

    /* ------------------------------------------------------------------ */
    /*  object model                                                      */
    /* ------------------------------------------------------------------ */

    abstract static class P { }

    static final class Name extends P {
        final String v;
        Name(String v) { this.v = v; }
    }

    static final class Num extends P {
        final String raw;
        Num(String raw) { this.raw = raw == null || raw.isEmpty() ? "0" : raw; }
        long longValue() {
            try { return Long.parseLong(raw.trim()); } catch (Exception e) { return 0; }
        }
    }

    /** Literal or hex string, kept exactly as it appeared (delimiters included). */
    static final class Str extends P {
        final byte[] raw;
        Str(byte[] raw) { this.raw = raw; }
    }

    static final class Ref extends P {
        final int num, gen;
        Ref(int num, int gen) { this.num = num; this.gen = gen; }
    }

    static final class Arr extends P {
        final List<P> items = new ArrayList<P>();
    }

    static final class Dict extends P {
        final LinkedHashMap<String, P> map = new LinkedHashMap<String, P>();
        P get(String k) { return map.get(k); }
        boolean is(String key, String name) {
            P v = map.get(key);
            return v instanceof Name && ((Name) v).v.equals(name);
        }
    }

    static final class Bool extends P {
        final boolean v;
        Bool(boolean v) { this.v = v; }
    }

    static final P NULL = new P() { };

    static final class Obj {
        final int num, gen;
        P val;
        int streamStart = -1;
        int streamLen = -1;
        boolean hasStream;
        Obj(int num, int gen, P val) { this.num = num; this.gen = gen; this.val = val; }
        Dict dict() { return val instanceof Dict ? (Dict) val : null; }
    }

    /* ------------------------------------------------------------------ */
    /*  entry point                                                       */
    /* ------------------------------------------------------------------ */

    static Report rewrite(File src, OutputStream out) throws IOException {
        Report rp = new Report();
        rp.in = src.length();
        RandomAccessFile raf = new RandomAccessFile(src, "r");
        try {
            long len = raf.length();
            if (len <= 0) throw new IOException("Empty PDF");
            ByteBuffer bb = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, len);
            Doc d = new Doc(bb);
            d.scan();
            d.indexObjectStreams();
            rp.objectsFound = d.objs.size();
            if (d.objs.isEmpty()) throw new IOException("No PDF objects found — the file is not a PDF or its header is corrupt");

            if (d.encrypted()) {
                rp.encrypted = true;
                return rp;
            }
            Ref root = d.root();
            if (root == null) throw new IOException("No document catalog found — the PDF trailer is missing or damaged");

            d.stripMetadata(root.num);       // before the walk, so the XMP packet is not kept alive
            Set<Integer> keep = d.reachable(root.num);
            Map<Integer, int[]> contained = d.objectStreamMembers(keep);   // objects living inside /ObjStm
            rp.objectStreams = !contained.isEmpty();

            long written = d.write(out, keep, contained, rp);
            rp.out = written;
            rp.objectsKept = keep.size();
            rp.objectsDropped = Math.max(0, rp.objectsFound - keep.size());
            rp.note = note(rp);
            return rp;
        } finally {
            try { raf.close(); } catch (Exception ignored) { }
        }
    }

    private static String note(Report rp) {
        StringBuilder b = new StringBuilder();
        b.append("PDF rewritten · ").append(rp.streamsReflated).append(" streams re-deflated");
        if (rp.objectsDropped > 0) b.append(" · ").append(rp.objectsDropped).append(" unused objects dropped");
        b.append(" · metadata stripped");
        return b.toString();
    }

    /* ------------------------------------------------------------------ */
    /*  parser                                                            */
    /* ------------------------------------------------------------------ */

    private static final class Doc {
        final ByteBuffer b;
        final int n;
        final Map<Integer, Obj> objs = new LinkedHashMap<Integer, Obj>();
        /** Object number -> {containing ObjStm number, index in it, offset of body}. */
        final Map<Integer, int[]> stmIndex = new LinkedHashMap<Integer, int[]>();
        final Map<Integer, byte[]> stmData = new LinkedHashMap<Integer, byte[]>();
        final Map<Integer, Integer> stmFirst = new LinkedHashMap<Integer, Integer>();

        Doc(ByteBuffer b) {
            this.b = b;
            this.n = b.limit();
        }

        int at(int i) { return i < 0 || i >= n ? -1 : (b.get(i) & 0xFF); }

        String text(int from, int len) {
            if (len <= 0) return "";
            byte[] t = new byte[len];
            ByteBuffer d = b.duplicate();
            d.position(from);
            d.get(t, 0, len);
            return new String(t, java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        byte[] bytes(int from, int len) {
            if (len <= 0) return new byte[0];
            byte[] t = new byte[len];
            ByteBuffer d = b.duplicate();
            d.position(from);
            d.get(t, 0, len);
            return t;
        }

        static boolean isWs(int c) {
            return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
        }

        static boolean isDelim(int c) {
            return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '/' || c == '%';
        }

        int ws(int i) {
            while (i < n) {
                int c = at(i);
                if (c == '%') {
                    while (i < n && at(i) != '\n' && at(i) != '\r') i++;
                } else if (isWs(c)) {
                    i++;
                } else {
                    break;
                }
            }
            return i;
        }

        boolean kw(int i, String s) {
            if (i < 0 || i + s.length() > n) return false;
            for (int k = 0; k < s.length(); k++) {
                if (at(i + k) != s.charAt(k)) return false;
            }
            int after = at(i + s.length());
            return after < 0 || isWs(after) || isDelim(after);
        }

        String token(int from) {
            int j = from;
            while (j < n && !isWs(at(j)) && !isDelim(at(j))) j++;
            return text(from, j - from);
        }

        int indexOfFrom(int from, String needle) {
            int m = needle.length();
            byte first = (byte) needle.charAt(0);
            for (int i = Math.max(0, from); i + m <= n; i++) {
                if (b.get(i) != first) continue;
                boolean ok = true;
                for (int k = 1; k < m; k++) {
                    if (at(i + k) != needle.charAt(k)) { ok = false; break; }
                }
                if (ok) return i;
            }
            return -1;
        }

        /* ---- scanning ---- */

        void scan() {
            int i = indexOfFrom(0, "%PDF");
            if (i < 0) i = 0;
            while (i < n) {
                i = ws(i);
                if (i >= n) break;
                int c = at(i);
                if (c >= '0' && c <= '9') {
                    int save = i;
                    String t = token(i);
                    if (t.indexOf('.') < 0) {
                        int j = ws(i + t.length());
                        int c2 = at(j);
                        if (c2 >= '0' && c2 <= '9') {
                            String t2 = token(j);
                            int k = ws(j + t2.length());
                            if (kw(k, "obj")) {
                                int num = parseInt(t);
                                int gen = parseInt(t2);
                                int[] cp = {k + 3};
                                P v = value(cp);
                                int after = ws(cp[0]);
                                Obj o = new Obj(num, gen, v);
                                if (kw(after, "stream")) {
                                    int ss = after + 6;
                                    if (at(ss) == '\r') ss++;
                                    if (at(ss) == '\n') ss++;
                                    int declared = -1;
                                    if (v instanceof Dict) {
                                        P L = ((Dict) v).get("Length");
                                        if (L instanceof Num) declared = (int) Math.min(Integer.MAX_VALUE, ((Num) L).longValue());
                                    }
                                    int se = streamEnd(ss, declared);
                                    o.streamStart = ss;
                                    o.streamLen = Math.max(0, se - ss);
                                    o.hasStream = true;
                                    if (v instanceof Dict) ((Dict) v).map.put("Length", new Num(String.valueOf(o.streamLen)));
                                    int es = indexOfFrom(se, "endstream");
                                    i = es >= 0 ? es + 9 : n;
                                } else {
                                    i = cp[0];
                                }
                                objs.put(num, o);   // a later definition (incremental update) wins
                                continue;
                            }
                        }
                    }
                    i = save + 1;
                } else {
                    i++;
                }
            }
        }

        int streamEnd(int ss, int declared) {
            if (declared >= 0 && ss + declared <= n) {
                int after = ws(ss + declared);
                if (kw(after, "endstream")) return ss + declared;
            }
            int es = indexOfFrom(ss, "endstream");
            if (es < 0) return n;
            int end = es;
            while (end > ss && (at(end - 1) == '\n' || at(end - 1) == '\r')) end--;
            return end;
        }

        /* ---- values ---- */

        P value(int[] cp) {
            int i = ws(cp[0]);
            cp[0] = i;
            if (i >= n) return NULL;
            int c = at(i);
            if (c == '<') {
                if (at(i + 1) == '<') return dict(cp);
                int j = i + 1;
                while (j < n && at(j) != '>') j++;
                if (j < n) j++;
                cp[0] = j;
                return new Str(bytes(i, j - i));
            }
            if (c == '[') return array(cp);
            if (c == '(') return literal(cp);
            if (c == '/') return name(cp);
            if (c == ']' || c == '>' || c == '}' || c == ')' || c == '{') { cp[0] = i + 1; return NULL; }
            if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') {
                String t = token(i);
                int j = i + t.length();
                int k = ws(j);
                int c2 = at(k);
                if (c2 >= '0' && c2 <= '9' && t.indexOf('.') < 0) {
                    String t2 = token(k);
                    int k2 = ws(k + t2.length());
                    if (kw(k2, "R")) {
                        cp[0] = k2 + 1;
                        return new Ref(parseInt(t), parseInt(t2));
                    }
                }
                cp[0] = j;
                return new Num(t);
            }
            String t = token(i);
            int j = i + Math.max(1, t.length());
            cp[0] = j;
            if ("true".equals(t)) return new Bool(true);
            if ("false".equals(t)) return new Bool(false);
            return NULL;
        }

        P dict(int[] cp) {
            int i = ws(cp[0]);
            i += 2;
            Dict d = new Dict();
            int guard = 0;
            while (i < n && guard++ < 2_000_000) {
                i = ws(i);
                if (i >= n) break;
                int c = at(i);
                if (c == '>' && at(i + 1) == '>') { i += 2; break; }
                if (c != '/') {
                    int before = i;
                    int[] c2 = {i};
                    value(c2);
                    i = Math.max(c2[0], before + 1);
                    continue;
                }
                int[] c2 = {i};
                P key = name(c2);
                if (!(key instanceof Name)) { i = Math.max(c2[0], i + 1); continue; }
                int[] c3 = {c2[0]};
                P v = value(c3);
                i = Math.max(c3[0], c2[0] + 1);
                d.map.put(((Name) key).v, v);
            }
            cp[0] = i;
            return d;
        }

        P array(int[] cp) {
            int i = cp[0] + 1;
            Arr a = new Arr();
            int guard = 0;
            while (i < n && guard++ < 2_000_000) {
                i = ws(i);
                if (i >= n) break;
                if (at(i) == ']') { i++; break; }
                int[] c2 = {i};
                a.items.add(value(c2));
                i = Math.max(c2[0], i + 1);
            }
            cp[0] = i;
            return a;
        }

        P literal(int[] cp) {
            int i = cp[0];
            int start = i;
            int depth = 0;
            while (i < n) {
                int c = at(i);
                if (c == '\\') { i += 2; continue; }
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) { i++; break; }
                }
                i++;
            }
            cp[0] = Math.min(i, n);
            return new Str(bytes(start, Math.min(i, n) - start));
        }

        P name(int[] cp) {
            int i = cp[0] + 1;
            int start = i;
            while (i < n && !isWs(at(i)) && !isDelim(at(i))) i++;
            cp[0] = i;
            return new Name(text(start, i - start));
        }

        static int parseInt(String s) {
            try {
                long v = Long.parseLong(s.trim());
                return (int) Math.max(0, Math.min(Integer.MAX_VALUE, v));
            } catch (Exception e) {
                return 0;
            }
        }

        /* ---- trailer ---- */

        Dict trailerDict() {
            Dict best = null;
            // Classic trailers.
            int i = 0;
            int guard = 0;
            while (guard++ < 100000) {
                i = indexOfFrom(i, "trailer");
                if (i < 0) break;
                int j = ws(i + 7);
                if (at(j) == '<' && at(j + 1) == '<') {
                    int[] cp = {j};
                    P v = dict(cp);
                    if (v instanceof Dict && ((Dict) v).get("Root") instanceof Ref) best = (Dict) v;
                }
                i += 7;
            }
            // Cross-reference streams carry the same keys in their own dictionary.
            for (Obj o : objs.values()) {
                Dict d = o.dict();
                if (d == null) continue;
                if (d.is("Type", "XRef") && d.get("Root") instanceof Ref) best = d;
            }
            return best;
        }

        boolean encrypted() {
            Dict t = trailerDict();
            if (t != null && t.get("Encrypt") != null) return true;
            for (Obj o : objs.values()) {
                Dict d = o.dict();
                if (d != null && d.is("Type", "Encrypt")) return true;
            }
            return false;
        }

        Ref root() {
            Dict t = trailerDict();
            if (t != null && t.get("Root") instanceof Ref) {
                Ref r = (Ref) t.get("Root");
                if (objs.containsKey(r.num)) return r;
            }
            for (Obj o : objs.values()) {
                Dict d = o.dict();
                if (d != null && d.is("Type", "Catalog")) return new Ref(o.num, o.gen);
            }
            return null;
        }

        /* ---- reachability ---- */

        Set<Integer> reachable(int rootNum) {
            Set<Integer> seen = new LinkedHashSet<Integer>();
            Deque<Integer> q = new ArrayDeque<Integer>();
            seen.add(rootNum);
            q.add(rootNum);
            while (!q.isEmpty()) {
                int num = q.poll();
                Obj o = objs.get(num);
                if (o != null) {
                    refs(o.val, seen, q);
                    continue;
                }
                // Not a top-level object: it may live inside an /ObjStm, in which case
                // the stream that carries it has to survive too.
                int[] loc = stmIndex.get(num);
                if (loc == null) continue;
                if (seen.add(loc[0])) q.add(loc[0]);
                P v = containedValue(num);
                if (v != null) refs(v, seen, q);
            }
            return seen;
        }

        /** Parse an object that is stored inside an object stream. */
        P containedValue(int num) {
            int[] loc = stmIndex.get(num);
            if (loc == null) return null;
            byte[] data = stmData.get(loc[0]);
            Integer first = stmFirst.get(loc[0]);
            if (data == null || first == null) return null;
            Doc sub = new Doc(ByteBuffer.wrap(data));
            int[] cp = {first + loc[2]};
            if (cp[0] < 0 || cp[0] >= data.length) return null;
            return sub.value(cp);
        }

        /** Every object stored in an object stream, with its stream and index. */
        void indexObjectStreams() {
            for (Obj o : new ArrayList<Obj>(objs.values())) {
                if (!o.hasStream) continue;
                Dict d = o.dict();
                if (d == null || !d.is("Type", "ObjStm")) continue;
                byte[] data = inflateStream(o);
                if (data == null) continue;
                int count = numOf(d.get("N"));
                int first = numOf(d.get("First"));
                if (count <= 0 || first <= 0 || first >= data.length) continue;
                stmData.put(o.num, data);
                stmFirst.put(o.num, first);
                String head = new String(data, 0, Math.min(first, 1 << 16),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                String[] parts = head.trim().split("\\s+");
                int idx = 0;
                for (int k = 0; k + 1 < parts.length && idx < count; k += 2, idx++) {
                    int objNum, off;
                    try {
                        objNum = Integer.parseInt(parts[k]);
                        off = Integer.parseInt(parts[k + 1]);
                    } catch (Exception e) {
                        break;
                    }
                    if (objNum <= 0) continue;
                    stmIndex.put(objNum, new int[]{o.num, idx, off});
                }
            }
        }

        static int numOf(P v) {
            if (v instanceof Num) return (int) Math.min(Integer.MAX_VALUE, ((Num) v).longValue());
            return 0;
        }

        void refs(P v, Set<Integer> seen, Deque<Integer> q) {
            if (v instanceof Ref) {
                int num = ((Ref) v).num;
                if (seen.add(num)) q.add(num);
            } else if (v instanceof Arr) {
                for (P p : ((Arr) v).items) refs(p, seen, q);
            } else if (v instanceof Dict) {
                for (P p : ((Dict) v).map.values()) refs(p, seen, q);
            }
        }

        /** Object numbers that live inside a kept /ObjStm, mapped to (stream, index). */
        Map<Integer, int[]> objectStreamMembers(Set<Integer> keep) {
            Map<Integer, int[]> inside = new LinkedHashMap<Integer, int[]>();
            for (Map.Entry<Integer, int[]> e : stmIndex.entrySet()) {
                int[] loc = e.getValue();
                if (!keep.contains(loc[0])) continue;      // its stream was not reached
                if (objs.containsKey(e.getKey())) continue; // a real top-level object wins
                inside.put(e.getKey(), new int[]{loc[0], loc[1]});
            }
            return inside;
        }

        byte[] inflateStream(Obj o) {
            if (o.streamLen <= 0 || o.streamStart < 0) return null;
            byte[] raw = bytes(o.streamStart, o.streamLen);
            Dict d = o.dict();
            String filter = d == null ? null : filterName(d);
            if (filter == null) return raw;
            if (!"FlateDecode".equals(filter)) return null;
            try {
                return inflate(raw);
            } catch (Exception e) {
                return null;
            }
        }

        /* ---- writer ---- */

        long write(OutputStream out, Set<Integer> keep, Map<Integer, int[]> contained, Report rp) throws IOException {
            Counter co = new Counter(out);
            co.write(("%PDF-1.7\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            co.write(new byte[]{0x25, (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

            List<Integer> nums = new ArrayList<Integer>(keep);
            java.util.Collections.sort(nums);

            int maxNum = 0;
            for (Integer k : objs.keySet()) maxNum = Math.max(maxNum, k);

            Map<Integer, long[]> xref = new LinkedHashMap<Integer, long[]>();   // num -> {offset, gen}
            for (Integer num : nums) {
                Obj o = objs.get(num);
                if (o == null) continue;
                Dict d = o.dict();
                if (d != null) {
                    if (d.is("Type", "XRef")) continue;                     // we write our own
                    if (d.get("Linearized") != null) continue;              // linearization dict: meaningless now
                }
                if (contained.containsKey(num)) continue;                   // lives inside an object stream
                strip(d);
                xref.put(num, new long[]{co.count, o.gen});
                writeObj(co, o, d, rp);
            }

            int size = Math.max(maxNum, 1) + 1;
            int xrefNum = size;
            boolean useStream = !contained.isEmpty();

            Dict trailer = trailerDict();
            P id = trailer == null ? null : trailer.get("ID");

            if (useStream) {
                xref.put(xrefNum, new long[]{co.count, 0});
                Dict xd = new Dict();
                xd.map.put("Type", new Name("XRef"));
                xd.map.put("Size", new Num(String.valueOf(size + 1)));
                Arr w = new Arr();
                w.items.add(new Num("1"));
                w.items.add(new Num("4"));
                w.items.add(new Num("2"));
                xd.map.put("W", w);
                Arr index = new Arr();
                index.items.add(new Num("0"));
                index.items.add(new Num(String.valueOf(size + 1)));
                xd.map.put("Index", index);
                Ref rr = root();
                if (rr != null) xd.map.put("Root", rr);
                if (id != null) xd.map.put("ID", id);

                byte[] table = new byte[(size + 1) * 7];
                for (int num = 0; num <= size; num++) {
                    int off = num * 7;
                    long[] e = xref.get(num);
                    if (num == 0) {
                        table[off] = 0;
                    } else if (e != null) {
                        table[off] = 1;
                        put4(table, off + 1, e[0]);
                        table[off + 5] = (byte) (e[1] >> 8);
                        table[off + 6] = (byte) e[1];
                    } else if (contained.containsKey(num)) {
                        int[] c = contained.get(num);
                        table[off] = 2;
                        put4(table, off + 1, c[0]);
                        table[off + 5] = (byte) (c[1] >> 8);
                        table[off + 6] = (byte) c[1];
                    } else {
                        table[off] = 0;
                    }
                }
                byte[] packed = deflate(table);
                if (packed == null) packed = table;
                else {
                    xd.map.put("Filter", new Name("FlateDecode"));
                }
                xd.map.put("Length", new Num(String.valueOf(packed.length)));

                long xoff = co.count;
                co.write((xrefNum + " 0 obj\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                writeValue(co, xd);
                co.write("\nstream\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                co.write(packed);
                co.write("\nendstream\nendobj\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                xref.put(xrefNum, new long[]{xoff, 0});

                co.write("startxref\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                co.write((xoff + "\n%%EOF\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            } else {
                long xrefPos = co.count;
                co.write("xref\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                StringBuilder sb = new StringBuilder();
                int num = 0;
                while (num <= maxNum) {
                    long[] e = num == 0 ? null : xref.get(num);
                    if (e == null && num != 0) { num++; continue; }
                    int run = 0;
                    int probe = num;
                    while (probe <= maxNum) {
                        long[] ee = probe == 0 ? null : xref.get(probe);
                        if (probe != 0 && ee == null) break;
                        run++;
                        probe++;
                    }
                    sb.append(num).append(' ').append(run).append('\n');
                    for (int k = 0; k < run; k++) {
                        int objNum = num + k;
                        if (objNum == 0) {
                            sb.append("0000000000 65535 f \n");
                        } else {
                            long[] ee = xref.get(objNum);
                            sb.append(pad10(ee[0])).append(' ').append(pad5(ee[1])).append(" n \n");
                        }
                    }
                    num = probe;
                }
                co.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

                StringBuilder tr = new StringBuilder();
                tr.append("trailer\n<< /Size ").append(size);
                Ref rr = root();
                if (rr != null) tr.append(" /Root ").append(rr.num).append(' ').append(rr.gen).append(" R");
                if (id instanceof Arr) {
                    ByteArrayOutputStream tmp = new ByteArrayOutputStream();
                    Counter cc = new Counter(tmp);
                    writeValue(cc, id);
                    tr.append(" /ID ").append(new String(tmp.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1));
                }
                tr.append(" >>\nstartxref\n").append(xrefPos).append("\n%%EOF\n");
                co.write(tr.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            co.flush();
            return co.count;
        }

        static void put4(byte[] t, int off, long v) {
            t[off] = (byte) (v >> 24);
            t[off + 1] = (byte) (v >> 16);
            t[off + 2] = (byte) (v >> 8);
            t[off + 3] = (byte) v;
        }

        static String pad10(long v) {
            String s = Long.toString(v);
            while (s.length() < 10) s = "0" + s;
            return s;
        }

        static String pad5(long v) {
            String s = Long.toString(v);
            while (s.length() < 5) s = "0" + s;
            return s;
        }

        /** Metadata never survives the rewrite. */
        void stripMetadata(int rootNum) {
            Obj o = objs.get(rootNum);
            Dict d = o == null ? null : o.dict();
            if (d == null) return;
            d.map.remove("Metadata");
            d.map.remove("PieceInfo");
            d.map.remove("SpiderInfo");
        }

        void strip(Dict d) {
            if (d == null) return;
            if (d.is("Type", "Catalog")) {
                d.map.remove("Metadata");
                d.map.remove("PieceInfo");
                d.map.remove("SpiderInfo");
            }
        }

        void writeObj(Counter co, Obj o, Dict d, Report rp) throws IOException {
            co.write((o.num + " " + o.gen + " obj\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            if (o.hasStream && d != null) {
                byte[] raw = bytes(o.streamStart, o.streamLen);
                byte[] data = raw;
                String filter = filterName(d);
                boolean hadFilter = filter != null;
                boolean canReflate = raw.length <= MAX_REFILTER
                        && (d.get("F") == null)                        // no external stream file
                        && (filter == null || "FlateDecode".equals(filter));

                if (canReflate) {
                    byte[] plain = raw;
                    if (hadFilter) {
                        try {
                            plain = inflate(raw);
                        } catch (Exception e) {
                            plain = null;
                        }
                    }
                    if (plain != null) {
                        byte[] packed = deflate(plain);
                        if (packed != null && packed.length < raw.length) {
                            data = packed;
                            if (!hadFilter) d.map.put("Filter", new Name("FlateDecode"));
                            if (!hadFilter) d.map.remove("DecodeParms");
                            rp.streamsReflated++;
                        } else {
                            rp.streamsCopied++;
                        }
                    } else {
                        rp.streamsCopied++;
                    }
                } else {
                    rp.streamsCopied++;
                }
                d.map.put("Length", new Num(String.valueOf(data.length)));
                writeValue(co, d);
                co.write("\nstream\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                co.write(data);
                co.write("\nendstream\nendobj\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            } else {
                writeValue(co, o.val);
                co.write("\nendobj\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }

        void writeValue(Counter co, P v) throws IOException {
            if (v == null) { co.write("null"); return; }
            if (v instanceof Name) {
                co.write("/");
                co.write(((Name) v).v.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            } else if (v instanceof Num) {
                co.write(((Num) v).raw.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            } else if (v instanceof Str) {
                co.write(((Str) v).raw);
            } else if (v instanceof Ref) {
                Ref r = (Ref) v;
                co.write((r.num + " " + r.gen + " R").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            } else if (v instanceof Bool) {
                co.write(((Bool) v).v ? "true" : "false");
            } else if (v instanceof Arr) {
                co.write("[");
                boolean first = true;
                for (P p : ((Arr) v).items) {
                    if (!first) co.write(" ");
                    writeValue(co, p);
                    first = false;
                }
                co.write("]");
            } else if (v instanceof Dict) {
                co.write("<<");
                for (Map.Entry<String, P> e : ((Dict) v).map.entrySet()) {
                    co.write(" /");
                    co.write(e.getKey().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    co.write(" ");
                    writeValue(co, e.getValue());
                }
                co.write(" >>");
            } else {
                co.write("null");
            }
        }

        static String filterName(Dict d) {
            P f = d.get("Filter");
            if (f instanceof Name) return ((Name) f).v;
            if (f instanceof Arr && ((Arr) f).items.size() == 1 && ((Arr) f).items.get(0) instanceof Name) {
                return ((Name) ((Arr) f).items.get(0)).v;
            }
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  deflate helpers                                                   */
    /* ------------------------------------------------------------------ */

    static byte[] inflate(byte[] in) throws DataFormatException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.length * 3));
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int got = inf.inflate(buf);
                if (got == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, got);
                if (out.size() > MAX_INFLATE) throw new DataFormatException("stream too large to re-compress");
            }
            if (out.size() == 0) return null;
            return out.toByteArray();
        } finally {
            inf.end();
        }
    }

    static byte[] deflate(byte[] in) {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            def.setInput(in);
            def.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.length / 2));
            byte[] buf = new byte[1 << 16];
            while (!def.finished()) {
                int got = def.deflate(buf);
                if (got > 0) out.write(buf, 0, got);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            def.end();
        }
    }

    /** Counts bytes written, so cross-reference offsets are exact. */
    private static final class Counter extends OutputStream {
        private final OutputStream o;
        long count;

        Counter(OutputStream o) { this.o = o; }

        @Override public void write(int b) throws IOException { o.write(b); count++; }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            count += len;
        }

        @Override public void write(byte[] b) throws IOException { write(b, 0, b.length); }

        void write(String s) throws IOException {
            write(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        }

        @Override public void flush() throws IOException { o.flush(); }
    }

    /* ------------------------------------------------------------------ */
    /*  a synthetic PDF, for the self-test                                */
    /* ------------------------------------------------------------------ */

    /**
     * A genuinely valid PDF with <b>uncompressed</b> content streams, built here so the
     * self-test can prove the rewriter shrinks a real document without shipping a
     * fixture. {@code pages} pages of vector text; the streams are raw operators, which
     * is exactly the case DEFLATE wins hardest on.
     */
    static byte[] sample(int pages) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Counter co = new Counter(out);
            co.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
            long[] offs = new long[3 * pages + 4];
            int[] nums = new int[offs.length];
            int fontNum = 3;
            int next = 4;   // 1 = catalog, 2 = pages, 3 = font

            int[] contentNum = new int[pages];
            int[] pageNum = new int[pages];
            for (int i = 0; i < pages; i++) {
                pageNum[i] = next++;
                contentNum[i] = next++;
            }

            offs[1] = co.count;
            co.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < pages; i++) kids.append(pageNum[i]).append(" 0 R ");
            offs[2] = co.count;
            co.write(("2 0 obj\n<< /Type /Pages /Count " + pages + " /Kids [" + kids.toString().trim() + "] >>\nendobj\n"));

            for (int i = 0; i < pages; i++) {
                offs[pageNum[i]] = co.count;
                co.write((pageNum[i] + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]"
                        + " /Resources << /Font << /F1 3 0 R >> >> /Contents " + contentNum[i] + " 0 R >>\nendobj\n"));

                StringBuilder cs = new StringBuilder();
                cs.append("BT /F1 18 Tf 60 760 Td (Squeeze self-test page ").append(i + 1).append(" of ").append(pages).append(") Tj ET\n");
                for (int line = 0; line < 40; line++) {
                    cs.append("BT /F1 11 Tf 60 ").append(700 - line * 16).append(" Td (")
                      .append("The quick brown fox jumps over the lazy dog ").append(line).append(") Tj ET\n");
                }
                byte[] data = cs.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                offs[contentNum[i]] = co.count;
                co.write((contentNum[i] + " 0 obj\n<< /Length " + data.length + " >>\nstream\n"));
                co.write(data);
                co.write("\nendstream\nendobj\n");
            }

            offs[fontNum] = co.count;
            co.write(fontNum + " 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

            int max = next - 1;
            long xref = co.count;
            co.write("xref\n0 " + (max + 1) + "\n");
            co.write("0000000000 65535 f \n");
            for (int i = 1; i <= max; i++) {
                co.write(Doc.pad10(offs[i]) + " 00000 n \n");
            }
            co.write("trailer\n<< /Size " + (max + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            co.flush();
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
