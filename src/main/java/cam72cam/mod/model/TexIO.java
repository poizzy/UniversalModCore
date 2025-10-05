package cam72cam.mod.model;

import cam72cam.mod.resource.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AITexel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.*;

public final class TexIO {
    private TexIO() {}

    public static class Lazy {
        protected volatile int id;
        protected ByteBuffer pixels;
        protected int w, h;

        private Lazy(ByteBuffer pixels, int w, int h) {
            this.pixels = pixels;
            this.w = w;
            this.h = h;
        }

        public int ensureId() {
            int existing = id;
            if (existing != 0) return existing;

            if (id != 0) return id;
            if (pixels == null) return 0;

            id = uploadGL(pixels, w, h, GL11.GL_RGBA);
            return id;
        }

        public void delete() {
            int tex = id;
            if (tex == 0) {
                if (pixels != null) {
                    STBImage.stbi_image_free(pixels);
                    pixels = null;
                }
                return;
            }
            Runnable r = () -> GL11.glDeleteTextures(tex);
            if (RenderSystem.isOnRenderThread()) r.run(); else RenderSystem.recordRenderCall(r::run);
            id = 0;
        }

        protected static int uploadGL(ByteBuffer pixels, int w, int h, int format) {
            int id = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    w, h,
                    0,
                    format,
                    GL11.GL_UNSIGNED_BYTE,
                    pixels
            );

            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                float maxAniso = GL30.glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                float desired = Math.min(16f, maxAniso);
                GL30.glTexParameterf(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, desired);
            } else {
                GL30.glTexParameterf(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_LOD_BIAS, -0.3f);
            }

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            STBImage.stbi_image_free(pixels);
            return id;
        }

    }

    // ----- New helpers for Assimp -----

    /** Create Lazy from an Assimp AITexture (embedded). */
    public static Lazy fromEmbedded(AITexture aiTex) {
        if (aiTex == null) return null;

        if (aiTex.mHeight() == 0) {
            // Compressed texture (mWidth = size in bytes)
            int size = aiTex.mWidth();
            long addr = aiTex.pcData().address();
            ByteBuffer encoded = MemoryUtil.memByteBuffer(addr, size);

            IntBuffer pw = BufferUtils.createIntBuffer(1);
            IntBuffer ph = BufferUtils.createIntBuffer(1);
            IntBuffer pc = BufferUtils.createIntBuffer(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, pw, ph, pc, 4);
            if (pixels == null) return null;
            return new Lazy(pixels, pw.get(0), ph.get(0));
        } else {
            // Raw RGBA texture
            int w = aiTex.mWidth();
            int h = aiTex.mHeight();
            int count = w * h;

            // Get texel buffer and restrict to 'count' elements
            AITexel.Buffer texels = aiTex.pcData();
            texels.limit(count);

            ByteBuffer bgra = MemoryUtil.memByteBuffer(texels.address(), count * AITexel.SIZEOF);
            // Note: This buffer is owned by Assimp; copy if you need persistence
            return new Lazy(bgra, w, h) {
                @Override
                public int ensureId() {
                    if (id != 0) return id;
                    if (pixels == null) return 0;
                    id = uploadGL(pixels, w, h, GL12.GL_BGRA);
                    return id;
                }
            };
        }

    }

    /** Load a texture from a plain file path string (relative to baseDir). */
    public static Lazy prepareLazy(Identifier baseDir, String relPath) {
        try {
            Identifier resolved = new Identifier(baseDir.getDomain(), baseDir.getPath() + "/" + relPath);
            ByteBuffer encoded = readAllBytesDirect(resolved);

            IntBuffer pw = BufferUtils.createIntBuffer(1);
            IntBuffer ph = BufferUtils.createIntBuffer(1);
            IntBuffer pc = BufferUtils.createIntBuffer(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, pw, ph, pc, 4);
            if (pixels == null) return null;

            return new Lazy(pixels, pw.get(0), ph.get(0));
        } catch (IOException e) {
            return null;
        }
    }

    public static Lazy fromIdentifier(Identifier id) {
        try {
            ByteBuffer encoded = readAllBytesDirect(id);
            IntBuffer pw = BufferUtils.createIntBuffer(1);
            IntBuffer ph = BufferUtils.createIntBuffer(1);
            IntBuffer pc = BufferUtils.createIntBuffer(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, pw, ph, pc, 4);
            if (pixels == null) return null;
            return new Lazy(pixels, pw.get(0), ph.get(0));
        } catch (IOException e) {
            return null;
        }
    }


    // --------- helpers (unchanged) ----------
    private static ByteBuffer decodeDataUriToDirect(String uri) throws IOException {
        int comma = uri.indexOf(',');
        if (comma < 0) throw new IOException("Malformed data URI");
        String meta = uri.substring(5, comma);
        String data = uri.substring(comma + 1);

        byte[] bytes = meta.endsWith(";base64")
                ? Base64.getDecoder().decode(data)
                : data.getBytes(StandardCharsets.UTF_8);

        ByteBuffer out = BufferUtils.createByteBuffer(bytes.length);
        out.put(bytes).flip();
        return out;
    }

    private static ByteBuffer readAllBytesDirect(Identifier id) throws IOException {
        try (InputStream in = id.getResourceStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(8192, in.available()))) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            byte[] arr = baos.toByteArray();
            ByteBuffer out = BufferUtils.createByteBuffer(arr.length);
            out.put(arr).flip();
            return out;
        }
    }

    private static ByteBuffer toDirect(ByteBuffer src) {
        if (src.isDirect() && src.order() == ByteOrder.nativeOrder()) {
            ByteBuffer dup = src.slice();
            dup.order(src.order());
            return dup;
        }
        ByteBuffer out = BufferUtils.createByteBuffer(src.remaining());
        out.put(src.duplicate()).flip();
        return out;
    }
}
