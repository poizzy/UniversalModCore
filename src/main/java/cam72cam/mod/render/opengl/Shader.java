package cam72cam.mod.render.opengl;

import cam72cam.mod.resource.Identifier;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL20.glUseProgram;

public class Shader {
    private int id; // May be final

    public Shader(Identifier vertex, Identifier fragment) {
        if (vertex == null || fragment == null) {
            return;
        }

        String vertSrc = identToString(vertex);
        String fragSrc = identToString(fragment);

        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, vertSrc);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException(glGetShaderInfoLog(vs));
        }

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, fragSrc);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException(glGetShaderInfoLog(fs));
        }

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);

        glBindAttribLocation(program, 0, "aPos");
        glBindAttribLocation(program, 1, "aNormal");
        glBindAttribLocation(program, 2, "aUV");
        glBindAttribLocation(program, 3, "aTangent");
        glBindAttribLocation(program, 4, "aBitangent");
        glBindAttribLocation(program, 5, "aBoneIds");
        glBindAttribLocation(program, 6, "aWeights");
        GL30.glBindFragDataLocation(program, 0, "FragColor");


        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException(glGetProgramInfoLog(program));
        }

        glValidateProgram(program);

        glDeleteShader(vs);
        glDeleteShader(fs);

        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "texture_diffuse1"), 0);
        glUniform1i(glGetUniformLocation(program, "texture_specular1"), 1);
        glUniform1i(glGetUniformLocation(program, "texture_normal1"), 2);
        glUniform1i(glGetUniformLocation(program, "texture_height1"), 3);
        glUseProgram(0);

        id = program;
    }

    public int getId() {
        return this.id;
    }

    private static String identToString(Identifier ident) {
        try (InputStream stream = ident.getResourceStream()) {
            return IOUtils.toString(stream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
