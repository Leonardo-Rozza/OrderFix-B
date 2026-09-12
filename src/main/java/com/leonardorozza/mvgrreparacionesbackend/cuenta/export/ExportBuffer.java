package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import java.io.ByteArrayOutputStream;

final class ExportBuffer extends ByteArrayOutputStream {
    private final int maximum;
    ExportBuffer(int maximum) { super(Math.min(8192, Math.max(0, maximum))); this.maximum = maximum; }
    private void check(int added) {
        if (added < 0 || added > maximum - count) throw new ExportPackageException(ExportPackageException.Code.CAPACITY_EXCEEDED);
    }
    @Override public synchronized void write(int value) { check(1); super.write(value); }
    @Override public synchronized void write(byte[] bytes, int offset, int length) { check(length); super.write(bytes, offset, length); }
}
