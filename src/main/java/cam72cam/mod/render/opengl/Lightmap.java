package cam72cam.mod.render.opengl;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL30.*;

public class Lightmap {
    private static Lightmap instance;

    private static final int SIZE = 16;
    private final int texId;
    private boolean shouldUpdate = false;

    public static Lightmap getInstance() {
        return instance != null ? instance : (instance = new Lightmap());
    }

    private Lightmap() {
        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);

        ByteBuffer buf = BufferUtils.createByteBuffer(SIZE * SIZE * 3);
        for (int block = 0; block < SIZE; block++){
            for (int sky = 0; sky < SIZE; sky++) {
                float br = computeBrightness(block, sky);

                int r = (int) (br * 255);
                int g = (int) (br * 255);
                int b = (int) (br * 255);

                buf.put((byte) r);
                buf.put((byte) g);
                buf.put((byte) b);
            }
        }
        buf.flip();

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, SIZE, SIZE, 0, GL_RGB, GL_UNSIGNED_BYTE, buf);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void tick() {
        shouldUpdate = true;
    }

    public void updateLightmap(ClientLevel level, float partialTicks) {
        if (!shouldUpdate) {
            return;
        }

        float sun = level.getSkyDarken(partialTicks);
        ByteBuffer buf = BufferUtils.createByteBuffer(16 * 16 * 3);

        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block ++) {
                float skyBrightness = getBrightness(level.dimensionType(), sky) * sun;
                float blockBrightness = getBrightness(level.dimensionType(), block) * 1.5f;

                float brightness = Math.min(1.0f, skyBrightness + blockBrightness);
                int v = (int) (brightness * 255);
                buf.put((byte) v).put((byte) v).put((byte) v);
            }
        }

        buf.flip();

        glBindTexture(GL_TEXTURE_2D, texId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 16, 16, GL_RGB, GL_UNSIGNED_BYTE, buf);

        glBindTexture(GL_TEXTURE_2D, 0);
        shouldUpdate = false;
    }

    /**
     * @see net.minecraft.client.renderer.LightTexture
     */
    public static float getBrightness(DimensionType dimensionType, int light) {
        float f = (float) light / 15f;
        float f1 = f / (4f - 3f * f);
        return f1 + dimensionType.ambientLight() * (1.0f - f1);
    }

    private float computeBrightness(int block, int sky) {
        float bf = block / 15f;
        float sf = sky / 15f;

        float bLight = bf / (4.0f - 3.0f * bf);
        float sLight = sf / (4.0f - 3.0f * sf);

        return Math.min(1.0f, bLight + sLight);
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texId);
    }

    public int getTexId() {
        return texId;
    }
}
