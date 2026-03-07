package wobble.core;

import java.util.concurrent.atomic.AtomicInteger;

public enum Wobble {
    INSTANCE;

    private final AtomicInteger sampleRate = new AtomicInteger(44100);
    private final AtomicInteger chunkSize = new AtomicInteger(128);
    private final AtomicInteger currentChunk = new AtomicInteger(0);

    public int getSampleRate() {
        return sampleRate.get();
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate.set(sampleRate);
    }

    public int getChunkSize() {
        return chunkSize.get();
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize.set(chunkSize);
    }

    public int getCurrentChunk() {
        return currentChunk.get();
    }

    public void incrementCurrentChunk() {
        currentChunk.incrementAndGet();
    }

    public int timeToSamples(float timeSecs) {
        return (int) (timeSecs * getSampleRate());
    }
}
