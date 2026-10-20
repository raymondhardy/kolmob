package net.sourceforge.kolmafia;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Standard input for the headless core. There is no keyboard on a phone, so scripts that ask something
 * (user_confirm, user_prompt, ...) read their answer from here; the app feeds it from a dialog.
 * Reads block until a line is fed, which is what KoLmafia's command reader expects of a terminal.
 */
final class KolStdin extends InputStream {
  static final KolStdin INSTANCE = new KolStdin();

  private final BlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();

  private KolStdin() {}

  void feed(String line) {
    for (byte b : (line + "\n").getBytes(StandardCharsets.UTF_8)) bytes.add(b & 0xff);
  }

  @Override
  public int read() {
    try {
      return bytes.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  @Override
  public int read(byte[] b, int off, int len) {
    if (len == 0) return 0;
    int first = read();
    if (first < 0) return -1;
    b[off] = (byte) first;
    int n = 1;
    while (n < len) {
      Integer next = bytes.poll();
      if (next == null) break;
      b[off + n++] = (byte) (int) next;
    }
    return n;
  }

  @Override
  public int available() {
    return bytes.size();
  }
}
