package wobble.core;

import java.nio.FloatBuffer;

public class Port {
    public Module module;
    public int id;

    public Port(Module module, int id) {
        this.module = module;
        this.id = id;
    }

    public FloatBuffer read() {
        module.pull();
        return module.read(id);
    }
}
