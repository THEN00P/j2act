package j2act.jakarta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import j2act.ServeResult;

/**
 * Writes a served page (ADR 0031). When its loading(...) boundaries are still loading the response
 * stays open: asynchronously, so no thread waits, or holding the request thread where a filter
 * in front of this one is not async-capable.
 */
public final class PageWriter {

  private PageWriter() {
  }

  public static void write(ServeResult result, HttpServletRequest request, HttpServletResponse response)
    throws IOException {
    ServletOutputStream out = response.getOutputStream();
    if (!result.streams()) {
      out.write(result.html().getBytes(StandardCharsets.UTF_8));
      return;
    }
    // nginx buffers responses by default, which would hold the boundaries back.
    response.setHeader("X-Accel-Buffering", "no");
    out.write(result.html().getBytes(StandardCharsets.UTF_8));
    out.flush();
    if (request.isAsyncSupported()) {
      AsyncContext async = request.startAsync();
      async.setTimeout(0);
      result.streamTo(new ServeResult.ChunkSink() {
        @Override public void write(String chunk) throws IOException {
          out.write(chunk.getBytes(StandardCharsets.UTF_8));
          out.flush();
        }

        @Override public void end() {
          async.complete();
        }
      });
      return;
    }
    CountDownLatch done = new CountDownLatch(1);
    result.streamTo(new ServeResult.ChunkSink() {
      @Override public void write(String chunk) throws IOException {
        out.write(chunk.getBytes(StandardCharsets.UTF_8));
        out.flush();
      }

      @Override public void end() {
        done.countDown();
      }
    });
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
