package cam72cam.mod.render.opengl;

import cam72cam.mod.ModCore;
import cam72cam.mod.resource.Identifier;

public enum ShaderType {
    ENTITY(new Shader(new Identifier(ModCore.MODID, "shader/vertex.glsl"), new Identifier(ModCore.MODID, "shader/fragment.glsl"))),
    // TODO implement other shader types
    ITEM(new Shader(null, null)),
    SPRITE(new Shader(null, null)),
    GUI(new Shader(null, null));

    private final Shader shader;

    ShaderType(Shader shader) {
        this.shader = shader;
    }

    public int getShaderProgram() {
        return shader.getId();
    }
}
