package cam72cam.mod.model;

import cam72cam.mod.Config;
import cam72cam.mod.render.opengl.Mesh;
import cam72cam.mod.resource.Identifier;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.model.obj.Vec2f;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.BufferUtils;
import util.Matrix4;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.lwjgl.assimp.Assimp.*;

public final class ModelLoader {

    private record TempFile(File model, File root) {
    }

    public static List<Mesh> loadAll(Identifier file) throws Exception {
        TempFile asPath = tryResolvePath(file);
        if (asPath != null) {
            try {
                return loadAllFromFile(asPath.model.toPath(), file);
            } finally {
                if (!Config.PersistentTempFolder) {
                    deleteRecursively(asPath.root);
                }
            }

        } else {
            ByteBuffer bytes = readAllBytes(file);
            return loadAllFromMemory(bytes, file);
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    public static List<Mesh> loadAllFromFile(Path path, Identifier originalId) throws Exception {
        long flags = baseImportFlags();
        AIScene scene = aiImportFile(path.toString(), (int) flags);
        if (scene == null) {
            throw new IOException("Assimp failed: " + aiGetErrorString());
        }
        try {
            return convertScene(scene, originalId);
        } finally {
            aiReleaseImport(scene);
        }
    }

    public static List<Mesh> loadAllFromMemory(ByteBuffer bytes, Identifier originalId) throws Exception {
        long flags = baseImportFlags();
        String typeHint = originalId.getPath().substring(originalId.getPath().lastIndexOf(".") + 1);
        AIScene scene = aiImportFileFromMemory(bytes, (int) flags, typeHint);
        if (scene == null) {
            throw new IOException("Assimp failed: " + aiGetErrorString());
        }
        try {
            return convertScene(scene, originalId);
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static long baseImportFlags() {
        return aiProcess_Triangulate
                | aiProcess_JoinIdenticalVertices
                | aiProcess_GenSmoothNormals
                | aiProcess_CalcTangentSpace
                | aiProcess_LimitBoneWeights
                | aiProcess_ImproveCacheLocality
                | aiProcess_OptimizeMeshes
                | aiProcess_OptimizeGraph
                | aiProcess_PreTransformVertices // we still apply matrices below, but this helps flatten odd cases
                | aiProcess_RemoveRedundantMaterials
                | aiProcess_SortByPType
                | aiProcess_FixInfacingNormals;
    }

    // ---------- Scene → Mesh conversion ----------

    private static List<Mesh> convertScene(AIScene scene, Identifier sourceId) {
        List<Mesh> out = new ArrayList<>();
        Matrix4 root = new Matrix4().setIdentity();

        // Use the scene’s root node; if multiple, they’re children of root
        AINode rootNode = scene.mRootNode();
        if (rootNode == null) return out;

        // For texture base directory (external files)
        Identifier baseDir = deriveBaseDir(sourceId);

        visitNode(scene, rootNode, root, baseDir, out);
        return out;
    }

    private static void visitNode(AIScene scene, AINode node, Matrix4 parentWorld,
                                  Identifier baseDir, List<Mesh> out) {
        Matrix4 local = fromAiMatrix(node.mTransformation());
        Matrix4 world = parentWorld.copy().multiply(local);

        // Convert meshes at this node
        int numMeshes = node.mNumMeshes();
        if (numMeshes > 0) {
            IntBuffer meshIndices = node.mMeshes(); // indices into scene.mMeshes()
            for (int i = 0; i < numMeshes; i++) {
                int meshIdx = meshIndices.get(i);
                AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(meshIdx));
                buildMeshFromAiMesh(scene, aiMesh, world, baseDir, out);
            }
        }

        // Recurse children
        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();
        for (int i = 0; i < numChildren; i++) {
            AINode child = AINode.create(children.get(i));
            visitNode(scene, child, world, baseDir, out);
        }
    }

    private static void buildMeshFromAiMesh(AIScene scene, AIMesh m, Matrix4 world,
                                            Identifier baseDir, List<Mesh> out) {
        int vcount = m.mNumVertices();
        AIVector3D.Buffer aiPos = m.mVertices();
        AIVector3D.Buffer aiNrm = m.mNormals();
        AIVector3D.Buffer aiTan = m.mTangents();
        AIVector3D.Buffer aiBit = m.mBitangents();

        // Assimp supports up to AI_MAX_NUMBER_OF_TEXTURECOORDS (8); we use channel 0 if present
        AIVector3D.Buffer aiUV0 = (m.mTextureCoords(0) != null) ? m.mTextureCoords(0) : null;

        // Build vertex list
        List<Mesh.Vertex> verts = new ArrayList<>(Math.max(0, vcount));
        for (int i = 0; i < vcount; i++) {
            Mesh.Vertex v = new Mesh.Vertex();

            // position
            AIVector3D p = aiPos.get(i);
            Vec3d wp = world.apply(new Vec3d(p.x(), p.y(), p.z()));
            v.position = wp;

            // normal (upper-left 3x3 then normalize)
            if (aiNrm != null) {
                AIVector3D n = aiNrm.get(i);
                double x = world.m00 * n.x() + world.m01 * n.y() + world.m02 * n.z();
                double y = world.m10 * n.x() + world.m11 * n.y() + world.m12 * n.z();
                double z = world.m20 * n.x() + world.m21 * n.y() + world.m22 * n.z();
                double len = Math.sqrt(x * x + y * y + z * z);
                if (len > 0) { x /= len; y /= len; z /= len; }
                v.normal = new Vec3d(x, y, z);
            }

            // uv0 (Assimp stores vec3; ignore z)
            if (aiUV0 != null) {
                AIVector3D uv = aiUV0.get(i);
                // If your textures look upside down, flip V: new Vec2f(uv.x(), 1.0f - uv.y())
                v.uv = new Vec2f(uv.x(),  1f - uv.y());
            }

            // tangent/bitangent
            if (aiTan != null) {
                AIVector3D t = aiTan.get(i);
                double x = world.m00 * t.x() + world.m01 * t.y() + world.m02 * t.z();
                double y = world.m10 * t.x() + world.m11 * t.y() + world.m12 * t.z();
                double z = world.m20 * t.x() + world.m21 * t.y() + world.m22 * t.z();
                v.tangent = new Vec3d(x, y, z);

                if (aiBit != null) {
                    AIVector3D b = aiBit.get(i);
                    double bx = world.m00 * b.x() + world.m01 * b.y() + world.m02 * b.z();
                    double by = world.m10 * b.x() + world.m11 * b.y() + world.m12 * b.z();
                    double bz = world.m20 * b.x() + world.m21 * b.y() + world.m22 * b.z();
                    v.bitangent = new Vec3d(bx, by, bz);
                } else if (v.normal != null) {
                    // Rebuild bitangent if missing: b = n × t
                    var n = v.normal; var tvec = v.tangent;
                    v.bitangent = new Vec3d(
                            n.y * tvec.z - n.z * tvec.y,
                            n.z * tvec.x - n.x * tvec.z,
                            n.x * tvec.y - n.y * tvec.x
                    );
                }
            }

            // Bone IDs/weights (left at defaults; integrate if you want skinning)
            verts.add(v);
        }

        // Indices (faces are triangles due to aiProcess_Triangulate)
        int faceCount = m.mNumFaces();
        int[] indices = new int[faceCount * 3];
        AIFace.Buffer faces = m.mFaces();
        int w = 0;
        for (int i = 0; i < faceCount; i++) {
            AIFace f = faces.get(i);
            if (f.mNumIndices() != 3) continue; // be safe
            indices[w++] = f.mIndices().get(0);
            indices[w++] = f.mIndices().get(1);
            indices[w++] = f.mIndices().get(2);
        }
        if (w != indices.length) {
            int[] trimmed = new int[w];
            System.arraycopy(indices, 0, trimmed, 0, w);
            indices = trimmed;
        }

        // Material → textures (diffuse/albedo, normal, specular, height)
        List<Mesh.Texture> texList = new ArrayList<>();
        int matIndex = m.mMaterialIndex();
        if (matIndex >= 0 && scene.mMaterials() != null && matIndex < scene.mMaterials().remaining()) {
            AIMaterial mat = AIMaterial.create(scene.mMaterials().get(matIndex));

            // Diffuse / base color
            extractMaterialTextures(mat, aiTextureType_DIFFUSE, "texture_diffuse", baseDir, scene, texList);

            // Normal map
            extractMaterialTextures(mat, aiTextureType_NORMALS, "texture_normal", baseDir, scene, texList);
            extractMaterialTextures(mat, aiTextureType_HEIGHT, "texture_height", baseDir, scene, texList); // often bump

            // Specular
            extractMaterialTextures(mat, aiTextureType_SPECULAR, "texture_specular", baseDir, scene, texList);
        }

        out.add(new Mesh(verts, indices, texList));
    }

    private static void extractMaterialTextures(AIMaterial mat, int aiType, String kind,
                                                Identifier baseDir, AIScene scene, List<Mesh.Texture> out) {
        int count = aiGetMaterialTextureCount(mat, aiType);
        if (count <= 0) return;

        AIString path = AIString.malloc();
        int[] uvIndexBuf = new int[1];
        for (int i = 0; i < count; i++) {
            if (aiGetMaterialTexture(mat, aiType, i, path, (int[]) null, uvIndexBuf, null, null, null, null) != aiReturn_SUCCESS) {
                continue;
            }
            String texPath = path.dataString();
            if (texPath == null || texPath.isEmpty()) continue;

            Mesh.Texture tex = new Mesh.Texture();
            tex.type = kind;
            tex.path = texPath;
            tex.uvChannel = uvIndexBuf[0];

            if (texPath.startsWith("*")) {
                int idx = Integer.parseInt(texPath.substring(1));
                AITexture aiTex = AITexture.create(scene.mTextures().get(idx));

                tex.lazy = TexIO.fromEmbedded(aiTex);
            } else {
                // External file
                // Hook into your file-path loader
                tex.lazy = TexIO.prepareLazy(baseDir, texPath);
            }

            out.add(tex);
        }
    }



    // ---------- Helpers ----------

    private static Matrix4 fromAiMatrix(AIMatrix4x4 a) {
        // Assimp is row-major; your Matrix4 ctor appears to be (m00 m01 m02 m03; ...)
        // Map directly: a.a1..a4 is first row, etc.
        return new Matrix4(
                a.a1(), a.a2(), a.a3(), a.a4(),
                a.b1(), a.b2(), a.b3(), a.b4(),
                a.c1(), a.c2(), a.c3(), a.c4(),
                a.d1(), a.d2(), a.d3(), a.d4()
        );
    }

    private static Identifier deriveBaseDir(Identifier source) {
        // Implement according to your Identifier API.
        // Fallback: return the same id; your TexIO can decide how to resolve relative paths.
        return source.getBaseDir();
    }

    private static TempFile tryResolvePath(Identifier id) {
        String folder = id.getBaseDir().getPath();
        int lastSlash = folder.lastIndexOf('/');
        folder = folder.substring(0, lastSlash);

        Map<ResourceLocation, Resource> siblings = Minecraft.getInstance().getResourceManager().listResources(folder, path -> true);

        File gameDir = FMLPaths.GAMEDIR.get().toFile();
        File tempDir = new File(gameDir, "temp/" + UUID.randomUUID());
        tempDir.mkdirs();

        // Try default resource locations (Resource Packs / Jar)
        for (Map.Entry<ResourceLocation, Resource> resources : siblings.entrySet()) {
            File loc = new File(tempDir, String.format("assets/%s/%s", resources.getKey().getNamespace(), resources.getKey().getPath()));
            loc.getParentFile().mkdirs();
            try (InputStream input = resources.getValue().open(); FileOutputStream fos = new FileOutputStream(loc)) {
                fos.write(input.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return new TempFile(new File(tempDir, String.format("assets/%s/%s", id.getDomain(), id.getPath())), tempDir);
    }

    private static File getFromConfigDir(Identifier path) {
        File temp = new File(FMLPaths.GAMEDIR.get().toFile(), "temp");
        temp.mkdirs();

        File unziped;
        if ((unziped = new File(temp, String.format("assets/%s/%s", path.getDomain(), path.getPath()))).exists()) {
            return unziped;
        }

        try {
            return unzip(path, temp);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static File unzip(Identifier path, File tempDir) throws IOException {
        File config = FMLPaths.CONFIGDIR.get().toFile();
        config.mkdirs();

        File folder = new File(config + File.separator + path.getDomain());
        if (folder.exists()) {
            if (folder.isDirectory()) {
                File[] files = folder.listFiles(((dir, name) -> name.endsWith(".zip")));
                for (File file : files) {
                    byte[] buffer = new byte[1024];
                    ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
                    ZipEntry zipEntry = zis.getNextEntry();
                    while (zipEntry != null) {
                        File newFile = newFile(tempDir, zipEntry);
                        if (zipEntry.isDirectory()) {
                            if (!newFile.isDirectory() && !newFile.mkdirs()) {
                                throw new IOException("Failed to create directory " + newFile);
                            }
                        } else {
                                File parent = newFile.getParentFile();
                                if (!parent.isDirectory() && parent.mkdirs()) {
                                    throw new IOException("Failed to create directory " + newFile);
                                }

                                FileOutputStream fos = new FileOutputStream(newFile);
                                int len;
                                while ((len = zis.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                                fos.close();
                            }
                            zipEntry = zis.getNextEntry();
                        }
                    }
                File[] folders = folder.listFiles(((dir, name) -> true));
                for (File dir : folders) {
                    Files.walk(dir.toPath()).forEach(source -> {
                        Path destinaton = Paths.get(tempDir.toString(), source.toString().substring(tempDir.toString().length()));
                        try {
                            Files.copy(source, destinaton);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        } else {
            folder.mkdirs();
        }

        File unziped;
        if ((unziped = new File(tempDir, String.format("assets/%s/%s", path.getDomain(), path.getDomain()))).exists()) {
            return unziped;
        }
        return null;
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    private static ByteBuffer readAllBytes(Identifier id) throws IOException {
        try (InputStream in = id.getResourceStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(8192, in.available()))) {

            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }

            byte[] arr = baos.toByteArray();
            ByteBuffer out = BufferUtils.createByteBuffer(arr.length);
            out.put(arr).flip();
            return out;
        }
    }

}
