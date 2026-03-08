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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Port)) return false;
        Port other = (Port) o;
        return this.id == other.id && this.module == other.module;
    }

    @Override
    public int hashCode() {
        int result = System.identityHashCode(module);
        result = 31 * result + id;
        return result;
    }
}
