package cam72cam.mod.model.common;

import cam72cam.mod.math.Vec3d;
import cam72cam.mod.model.obj.*;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

//VertexBuffer wrapper, try to make it painless to migrate to EBO
public class FaceAccessor implements Iterable<FaceAccessor> {
    public static final HashMap<Integer, VertexBuffer> vbos = new HashMap<>();

    public final OBJModel model;
    private final VertexBuffer vbo;
    public VertexAccessor v0;
    public VertexAccessor v1;
    public VertexAccessor v2;

    private final boolean canSplit;
    private int currentFaceIndex;
    private final int startFace;
    private final int endFace;

    public FaceAccessor(OBJModel model) {
        this(model, 0, Integer.MAX_VALUE);
    }

    public FaceAccessor(OBJModel model, int startFace, int endFace) {
        this(model, startFace, endFace, true);
    }

    public FaceAccessor(OBJModel model, int startFace, int endFace, boolean canSplit) {
        this.model = model;
        this.vbo = vbos.computeIfAbsent(model.hashCode(), i -> model.vbo.buffer.get());
        int faceCount = this.vbo.data.length / this.vbo.stride / this.vbo.vertsPerFace;
        v0 = new VertexAccessor(0);
        v1 = new VertexAccessor(1);
        v2 = new VertexAccessor(2);
        if (endFace < startFace) {
            throw new IllegalStateException();
        }
        this.startFace = Math.max(0, startFace);
        this.endFace = Math.min(faceCount, endFace);
        this.currentFaceIndex = this.startFace;
        this.canSplit = canSplit;
    }

    public FaceAccessor getSubByGroup(String groupName) {
        if (!model.groups.containsKey(groupName) || !canSplit) {
            return null;
        }
        OBJGroup group = model.groups.get(groupName);
        return new FaceAccessor(model, group.faceStart, group.faceStop + 1, false);
    }

    public OBJFace asOBJFace() {
        OBJFace face = new OBJFace();
        face.vertices.add(v0.posAsVec3d());
        face.vertices.add(v1.posAsVec3d());
        face.vertices.add(v2.posAsVec3d());
        face.uv.add(v0.uvAsVec2f());
        face.uv.add(v1.uvAsVec2f());
        face.uv.add(v2.uvAsVec2f());
        if (vbo.hasNormals) {
            face.normal = v0.normAsVec3d();
        } else {
            Vec3d v0 = face.vertices.get(0);
            Vec3d v1 = face.vertices.get(1);
            Vec3d v2 = face.vertices.get(2);
            face.normal = v1.subtract(v0).crossProduct(v2.subtract(v0)).normalize();
        }
        return face;
    }

    /**
     * Don't store data in loop! This is a tricky hack to make it work with for-each
     * @return Iterator of self, only recommend to use in for-each
     */
    @Override
    @Nonnull
    public Iterator<FaceAccessor> iterator() {
        return new FaceIterator();
    }

    private class FaceIterator implements Iterator<FaceAccessor> {
        private int iteratorIndex;

        public FaceIterator() {
            this.iteratorIndex = startFace;
            currentFaceIndex = startFace;
        }

        @Override
        public boolean hasNext() {
            return iteratorIndex < endFace;
        }

        @Override
        public FaceAccessor next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            currentFaceIndex = iteratorIndex++;
            return FaceAccessor.this;
        }

        @Override
        public void remove() {
            Iterator.super.remove();
        }
    }

    public class VertexAccessor {
        public final int vertOffset;

        public VertexAccessor(int vertOffset) {
            this.vertOffset = vertOffset;
        }

        public Vec3d posAsVec3d() {
            return new Vec3d(x(), y(), z());
        }

        public Vec2f uvAsVec2f() {
            return new Vec2f(u(), v());
        }

        public Vec3d normAsVec3d() {
            if (!vbo.hasNormals) {
                return Vec3d.ZERO;
            }
            return new Vec3d(nx(), ny(), nz());
        }

        public float x() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset];
        }

        public float y() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 1];
        }

        public float z() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 2];
        }

        public float u() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 3];
        }

        public float v() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 4];
        }

        public float r() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 5];
        }

        public float g() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 6];
        }

        public float b() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 7];
        }

        public float a() {
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 8];
        }

        public float nx() {
            if (!vbo.hasNormals) {
                return 0;
            }
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 9];
        }

        public float ny() {
            if (!vbo.hasNormals) {
                return 0;
            }
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 10];
        }

        public float nz() {
            if (!vbo.hasNormals) {
                return 0;
            }
            return vbo.data[(currentFaceIndex * 3 + vertOffset) * vbo.stride + vertOffset + 11];
        }
    }
}
