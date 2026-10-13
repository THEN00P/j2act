import type { FileFields, Send } from "./protocol.ts";
import { backoff, named } from "./util.ts";

const CHUNK = 512 * 1024;

/** What an Upload passed to a client becomes (ADR 0006): resumable chunks and the server's limits, from JS. */
export interface UploadTarget {
  send(blob: Blob, name?: string): Promise<void>;
}

export type Uploads = ReturnType<typeof createUploads>;

/**
 * Uploads (ADR 0006): the event names the file, the server answers with where to send it, and
 * the chunks go over HTTP.
 */
export function createUploads(send: Send, token: () => string | null) {
  const files = new Map<string, Blob>();             // file id -> its bytes, until its upload ends
  const waiters = new Map<string, { resolve: () => void; reject: (e: Error) => void }>();
  let seq = 0;

  function settle(id: string, error: Error | null): void {
    const waiter = waiters.get(id);
    waiters.delete(id);
    if (waiter && error) {
      waiter.reject(error);
    } else if (waiter) {
      waiter.resolve();
    }
  }

  return {
    /** Files still being sent. */
    get size(): number {
      return files.size;
    },

    /** A file input changed: its event names the file, which waits here for the server's go. */
    picked(input: HTMLInputElement): FileFields {
      const file = input.files && input.files[0];
      if (!file) {
        return {};
      }
      const id = String(++seq);
      files.set(id, file);
      return { fileId: id, fileName: file.name, fileSize: file.size, fileType: file.type || "" };
    },

    target(handler: string): UploadTarget {
      return {
        send: (blob, name) => {
          return new Promise((resolve, reject) => {
            const fileId = String(++seq);
            files.set(fileId, blob);
            waiters.set(fileId, { resolve: resolve, reject: reject });
            send({ type: "callback", handler: handler, args: "[]", fileId: fileId, fileName: name || (blob as File).name || "blob",
              fileSize: blob.size, fileType: blob.type || "" });
          });
        }
      };
    },

    /** Refused before the first chunk: drop the file, reject an UploadTarget.send. */
    refused(id: string, message: string): void {
      files.delete(id);
      settle(id, named("NotAllowedError", message));
    },

    /**
     * Sends the file in order. The server's offset decides where each chunk starts, so a failed
     * or repeated chunk resumes where the bytes really stopped.
     */
    upload(id: string, url: string): void {
      const file = files.get(id);
      if (!file) {
        return; // gone after a reload: the server fails the run as stalled
      }
      let offset = 0;
      let failures = 0;
      const again = () => {
        if (++failures > 8) {
          files.delete(id);
          settle(id, named("NetworkError", "the upload kept failing"));
          return;
        }
        setTimeout(next, backoff(failures));
      };
      const next = () => {
        fetch(url + "?o=" + offset, {
          method: "POST",
          credentials: "same-origin",
          headers: { "X-J2-Token": token() || "", "Content-Type": "application/octet-stream" },
          body: file.slice(offset, offset + CHUNK)
        }).then((r) => {
          return r.json().catch(() => ({})).then((j: { o?: unknown; done?: boolean }) => ({ status: r.status, body: j }));
        }).then((res) => {
          if ((res.status === 200 || res.status === 409) && typeof res.body.o === "number") {
            failures = 0;
            offset = res.body.o;
            if (res.body.done) {
              files.delete(id);
              settle(id, null);
            } else {
              next();
            }
          } else if (res.status === 429) {
            again(); // over the session's rate limit: back off, the bytes are still wanted
          } else {
            files.delete(id); // refused: the server already failed the run
            settle(id, named("NotAllowedError", "the server refused the upload"));
          }
        }, again);
      };
      next();
    }
  };
}
