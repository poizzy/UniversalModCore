package cam72cam.mod.render.opengl;

import cam72cam.mod.util.With;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL20.*;

public class ShaderProgram {
    public static With apply(RenderState state, ShaderType shaderType) {
        int shaderProgram = shaderType.getShaderProgram();

        glDisable(GL_CULL_FACE);

        glUseProgram(shaderProgram);

        if (state.model_view != null && state.projection != null) {
            int locMV = glGetUniformLocation(shaderProgram, "uModelView");
            int locP = glGetUniformLocation(shaderProgram, "uProj");

            FloatBuffer fbMV = BufferUtils.createFloatBuffer(16);
            FloatBuffer fbP = BufferUtils.createFloatBuffer(16);

            state.model_view.get(fbMV).flip();
            state.projection.get(fbP).flip();

            glUniformMatrix4fv(locMV, true, fbMV);
            glUniformMatrix4fv(locP, true, fbP);
        }


        int locLM = glGetUniformLocation(shaderProgram, "uLightmap");
        int locLight = glGetUniformLocation(shaderProgram, "uLight");
        if (locLM >= 0 && locLight >= 0) {
            Lightmap.getInstance().bind(1);
            glUniform1i(locLM, 1);

            float bx = 1, sy = 1;
            if (state.lightmap != null) {
                bx = state.lightmap[0];
                sy = state.lightmap[1];
            }

            glUniform2f(locLight, bx, sy);
        }

        return () -> {
            glUseProgram(0);
            glEnable(GL_CULL_FACE);
        };
    }

}
