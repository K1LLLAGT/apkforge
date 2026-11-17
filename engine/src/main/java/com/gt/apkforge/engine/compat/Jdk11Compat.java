package com.gt.apkforge.engine.compat;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.Inflater;

/**
 * Static stand-ins for Java 9-11 APIs missing on Android API 26-34. Library call sites
 * are redirected here at build time by buildSrc/ApiCompatTransform. Do not call any of
 * the replaced APIs from this class.
 */
@SuppressWarnings("unused")
public final class Jdk11Compat {
    private Jdk11Compat() {}

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, in.available()));
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public static byte[] readNBytes(InputStream in, int len) throws IOException {
        if (len < 0) throw new IllegalArgumentException("len < 0");
        byte[] buf = new byte[len];
        int n = readNBytes(in, buf, 0, len);
        return n == len ? buf : Arrays.copyOf(buf, n);
    }

    public static int readNBytes(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(b, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    public static long transferTo(InputStream in, OutputStream out) throws IOException {
        long total = 0;
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    public static String toString(ByteArrayOutputStream out, Charset cs) {
        return new String(out.toByteArray(), cs);
    }

    public static void setInput(Inflater inflater, ByteBuffer buf) {
        byte[] arr = new byte[buf.remaining()];
        buf.duplicate().get(arr);
        inflater.setInput(arr);
    }

    public static String getTypeName(Type type) {
        if (type instanceof Class) {
            Class<?> c = (Class<?>) type;
            if (c.isArray()) return getTypeName(c.getComponentType()) + "[]";
            return c.getName();
        }
        return type.toString();
    }

    public static Path pathOf(String first, String[] more) {
        return Paths.get(first, more);
    }

    public static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    public static String readString(Path path, Charset cs) throws IOException {
        return new String(Files.readAllBytes(path), cs);
    }

    public static Path writeString(Path path, CharSequence text, OpenOption[] options) throws IOException {
        return Files.write(path, text.toString().getBytes(StandardCharsets.UTF_8), options);
    }

    public static Path writeString(Path path, CharSequence text, Charset cs, OpenOption[] options) throws IOException {
        return Files.write(path, text.toString().getBytes(cs), options);
    }

    public static Writer writer(File file, Charset cs) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), cs));
    }

    public static Writer writer(OutputStream out, boolean autoFlush, Charset cs) {
        return new BufferedWriter(new OutputStreamWriter(out, cs));
    }

    /** jadx Res9patchStreamDecoder.decode needs java.awt; report "not decoded" instead. */
    public static boolean decode9patch(Object decoder, InputStream in, OutputStream out) {
        return false;
    }
}
