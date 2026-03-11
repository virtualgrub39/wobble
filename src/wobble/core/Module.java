package wobble.core;

import java.nio.Buffer;
import java.nio.FloatBuffer;

public abstract class Module {
    public int lastComputedChunk = 0;

    public abstract void compute();

    public abstract Buffer read(int id);

    public boolean pull() {
        if (Wobble.INSTANCE.getCurrentChunk() > lastComputedChunk) {
            compute();
            lastComputedChunk = Wobble.INSTANCE.getCurrentChunk();
            return true;
        }
        return false;
    }

    public Port output(Integer id) {
        if (id == null) id = 0;
        return new Port(this, id);
    }

    public Port output() {
        return output(null);
    }
}
