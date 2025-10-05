package cam72cam.mod.render.opengl;

import cam72cam.mod.model.TexIO;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.model.obj.Vec2f;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL32.*;

public class Mesh {
    private static final int MAX_BONE_INFLUENCE = 4;

    // Float layout (interleaved float VBO): pos(3) + normal(3) + uv(2) + tangent(3) + bitangent(3) + weights(4) = 18 floats
    private static final int F_POS = 0;
    private static final int F_NORMAL = F_POS + 3;
    private static final int F_UV = F_NORMAL + 3;
    private static final int F_TANGENT = F_UV + 2;
    private static final int F_BITANGENT = F_TANGENT + 3;
    private static final int F_WEIGHTS = F_BITANGENT + 3;
    private static final int FLOATS_PER_VERTEX = 18;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    public static class Vertex {
        public Vec3d position = new Vec3d(0, 0, 0);
        public Vec3d normal = new Vec3d(0, 0, 0);
        public Vec2f uv = new Vec2f(0, 0);
        public Vec3d tangent = new Vec3d(0, 0, 0);
        public Vec3d bitangent = new Vec3d(0, 0, 0);
        public final int[] boneIds = new int[MAX_BONE_INFLUENCE];
        public final float[] weights = new float[MAX_BONE_INFLUENCE];

        void putFloats(FloatBuffer fb) {
            fb.put((float) position.x).put((float) position.y).put((float) position.z);
            fb.put((float) normal.x).put((float) normal.y).put((float) normal.z);
            fb.put(uv.x).put(uv.y);
            fb.put((float) tangent.x).put((float) tangent.y).put((float) tangent.z);
            fb.put((float) bitangent.x).put((float) bitangent.y).put((float) bitangent.z);
            fb.put(weights[0]).put(weights[1]).put(weights[2]).put(weights[3]);
        }

        void putBoneIds(IntBuffer ib) {
            ib.put(boneIds[0]).put(boneIds[1]).put(boneIds[2]).put(boneIds[3]);
        }
    }

    public static class Texture {
        public int id;
        public String type; // "texture_diffuse", "texture_specular", "texture_normal", "texture_height"
        public String path;
        public TexIO.Lazy lazy;
        public int uvChannel = 0;
    }

    public List<Vertex> vertices;
    public int[] indices;
    public List<Texture> textures;

    private int vao, vboFloats, vboBones, ebo;

    private Runnable pendingUpload;

    public Mesh(List<Vertex> vertices, int[] indices, List<Texture> textures) {
        this.vertices = vertices;
        this.indices = indices;
        this.textures = textures;

        setupMesh();
    }

    public void draw() {
        if (!isLoaded()) {
            setupMesh();
        }

        int prevVAO = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);

        bindUnit(0, findTex("texture_diffuse"));
        bindUnit(1, findTex("texture_specular"));
        bindUnit(2, findTex("texture_normal"));
        bindUnit(3, findTex("texture_height"));

        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indices.length, GL_UNSIGNED_INT, 0L);

        glBindVertexArray(prevVAO);
        glActiveTexture(prevActiveTex);
    }

    private void bindUnit(int unit, Texture tex) {
        if (tex == null) return;
        int id = 0;
        if (tex != null) {
            id = tex.id != 0 ? tex.id : (tex.lazy != null ? tex.lazy.ensureId() : 0);
            tex.id = id;
        }
        glActiveTexture(GL_TEXTURE0 + unit);
        if (id != 0) glBindTexture(GL_TEXTURE_2D, id);
    }

    private Texture findTex(String kind) {
        for (Texture t : textures) {
            if (t.type.startsWith(kind)) return t;
        }
        return null;
    }

    public void cleanup() {


        Runnable r = () -> {
            if (textures != null) {
                for (Texture t : textures) {
                    if (t.id != 0) glDeleteTextures(t.id);
                    t.id = 0;
                }
            }

            if (ebo != 0) glDeleteBuffers(ebo);
            if (vboFloats != 0) glDeleteBuffers(vboFloats);
            if (vboBones != 0) glDeleteBuffers(vboBones);
            if (vao != 0) glDeleteVertexArrays(vao);
            vao = vboFloats = vboBones = ebo = 0;
        };

        if (RenderSystem.isOnRenderThread()) r.run();
        else RenderSystem.recordRenderCall(r::run);
    }

    private boolean isLoaded() {
        return ebo != 0 && vboFloats != 0 && vao != 0;
    }

    private void setupMesh() {
        FloatBuffer floatBuf = BufferUtils.createFloatBuffer(vertices.size() * FLOATS_PER_VERTEX);
        IntBuffer   boneBuf  = BufferUtils.createIntBuffer(vertices.size() * MAX_BONE_INFLUENCE);
        for (Vertex v : vertices) { v.putFloats(floatBuf); v.putBoneIds(boneBuf); }
        floatBuf.flip(); boneBuf.flip();

        IntBuffer indexBuf = BufferUtils.createIntBuffer(indices.length);
        indexBuf.put(indices).flip();

        Runnable upload = () -> {
            int prevVAO = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int prevARRAY = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int prevELEM = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);


            vao = glGenVertexArrays();
            vboFloats = glGenBuffers();
            vboBones  = glGenBuffers();
            ebo       = glGenBuffers();

            glBindVertexArray(vao);

            glBindBuffer(GL_ARRAY_BUFFER, vboFloats);
            glBufferData(GL_ARRAY_BUFFER, floatBuf, GL_STATIC_DRAW);

            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE_BYTES, (long)F_POS * 4);

            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 3, GL_FLOAT, false, STRIDE_BYTES, (long)F_NORMAL * 4);

            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, STRIDE_BYTES, (long)F_UV * 4);

            glEnableVertexAttribArray(3);
            glVertexAttribPointer(3, 3, GL_FLOAT, false, STRIDE_BYTES, (long)F_TANGENT * 4);

            glEnableVertexAttribArray(4);
            glVertexAttribPointer(4, 3, GL_FLOAT, false, STRIDE_BYTES, (long)F_BITANGENT * 4);

            glEnableVertexAttribArray(6);
            glVertexAttribPointer(6, 4, GL_FLOAT, false, STRIDE_BYTES, (long)F_WEIGHTS * 4L); // byte offset!

            glBindBuffer(GL_ARRAY_BUFFER, vboBones);
            glBufferData(GL_ARRAY_BUFFER, boneBuf, GL_STATIC_DRAW);

            glEnableVertexAttribArray(5);
            glVertexAttribIPointer(5, 4, GL_INT, 4 * 4, 0L); // integer variant for bone IDs

            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuf, GL_STATIC_DRAW);

            glBindVertexArray(prevVAO);
            glBindBuffer(GL_ARRAY_BUFFER, prevARRAY);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, prevELEM);
        };

        if (RenderSystem.isOnRenderThread()) upload.run();
        else {
            pendingUpload = upload;
            RenderSystem.recordRenderCall(upload::run);
        }
    }
}
