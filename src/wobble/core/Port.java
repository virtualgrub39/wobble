package wobble.core;

import java.nio.FloatBuffer;

public class Port {
    public Module module;
    public int id;

    public Port(Module module, int id) {
        this.module = module;
        this.id = id;
    }

    public FloatBuffer read(Integer id) {
        if (id == null) id = 0;
        return module.read(id);
    }

    public FloatBuffer read() {
        return read(null);
    }
}
