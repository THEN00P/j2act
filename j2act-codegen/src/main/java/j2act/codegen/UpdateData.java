package j2act.codegen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Refreshes the vendored inputs in src/main/resources/data. VS Code's HTML data is
 * kept whole with its license; MDN's compat data is trimmed to the HTML status
 * fields we use; webref is trimmed to element names and their DOM interfaces. The window()
 * facade's IDL is kept whole for the specs window-api.json lists. Pinned versions live here.
 */
public final class UpdateData {

  static final String VSCODE = "@vscode/web-custom-data@0.6.3";
  static final String BCD = "@mdn/browser-compat-data@8.1.2";
  static final String WEBREF = "@webref/elements@2.9.0";
  static final String WEBREF_IDL = "@webref/idl@3.84.0";

  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

  private UpdateData() {
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args.length > 0 ? args[0] + "/j2act-codegen" : ".", "src/main/resources/data");
    Files.createDirectories(out);

    write(out.resolve("html-data.json"), fetch(VSCODE, "data/browsers.html-data.json"));
    write(out.resolve("LICENSE-vscode-web-custom-data.md"), fetch(VSCODE, "LICENSE.md"));

    JsonNode bcdRoot = JSON.readTree(fetch(BCD, "data.json"));
    JsonNode bcd = bcdRoot.path("html");
    ObjectNode status = JSON.createObjectNode();
    status.put("source", BCD);
    ObjectNode elements = status.putObject("elements");
    for (Iterator<Map.Entry<String, JsonNode>> it = bcd.path("elements").fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> element = it.next();
      ObjectNode e = trim(element.getValue());
      ObjectNode attributes = e.putObject("attributes");
      element.getValue().fields().forEachRemaining(attr -> {
        if (!attr.getKey().equals("__compat") && attr.getValue().has("__compat")) {
          attributes.set(attr.getKey(), trim(attr.getValue()));
        }
      });
      elements.set(element.getKey(), e);
    }
    ObjectNode globals = status.putObject("globalAttributes");
    bcd.path("global_attributes").fields().forEachRemaining(g -> globals.set(g.getKey(), trim(g.getValue())));
    write(out.resolve("bcd-html-status.json"), JSON.writeValueAsString(status));

    // The window() facade (ADR 0022): curated specs' IDL whole, BCD status for their interfaces.
    JsonNode curated = JSON.readTree(Files.readAllBytes(out.resolve("window-api.json")));
    Files.createDirectories(out.resolve("idl"));
    for (JsonNode spec : curated.path("specs")) {
      write(out.resolve("idl/" + spec.asText() + ".idl"), fetch(WEBREF_IDL, spec.asText() + ".idl"));
    }
    ObjectNode api = JSON.createObjectNode();
    api.put("source", BCD);
    ObjectNode apiInterfaces = api.putObject("interfaces");
    java.util.Set<String> apiNames = new java.util.TreeSet<>();
    curated.path("facades").fieldNames().forEachRemaining(apiNames::add);
    curated.path("statics").fieldNames().forEachRemaining(apiNames::add);
    curated.path("snapshots").forEach(n -> apiNames.add(n.asText()));
    for (String name : apiNames) {
      JsonNode bcdInterface = bcdRoot.path("api").path(name);
      ObjectNode entry = trim(bcdInterface);
      ObjectNode members = entry.putObject("members");
      bcdInterface.fields().forEachRemaining(member -> {
        if (!member.getKey().equals("__compat") && member.getValue().has("__compat")) {
          members.set(member.getKey(), trim(member.getValue()));
        }
      });
      apiInterfaces.set(name, entry);
    }
    write(out.resolve("bcd-api-status.json"), JSON.writeValueAsString(api));

    JsonNode webref = JSON.readTree(fetch(WEBREF, "html.json"));
    ObjectNode spec = JSON.createObjectNode();
    spec.put("source", WEBREF);
    ArrayNode names = spec.putArray("elements");
    ObjectNode interfaces = spec.putObject("interfaces");
    webref.path("elements").forEach(e -> {
      names.add(e.path("name").asText());
      interfaces.put(e.path("name").asText(), e.path("interface").asText());
    });
    write(out.resolve("webref-html-elements.json"), JSON.writeValueAsString(spec));
    // The npm package ships no LICENSE file; package.json says MIT and the text lives in the repo.
    write(out.resolve("LICENSE-webref.md"), fetchUrl("https://raw.githubusercontent.com/w3c/webref/main/LICENSE"));

    System.out.println("Updated data in " + out.toAbsolutePath().normalize());
  }

  private static ObjectNode trim(JsonNode node) {
    JsonNode compat = node.path("__compat");
    ObjectNode t = JSON.createObjectNode();
    if (compat.has("mdn_url")) {
      t.put("mdn", compat.path("mdn_url").asText());
    }
    JsonNode status = compat.path("status");
    t.put("deprecated", status.path("deprecated").asBoolean(false));
    t.put("experimental", status.path("experimental").asBoolean(false));
    t.put("standard", status.path("standard_track").asBoolean(true));
    return t;
  }

  private static String fetch(String pkg, String file) throws IOException, InterruptedException {
    return fetchUrl("https://cdn.jsdelivr.net/npm/" + pkg + "/" + file);
  }

  private static String fetchUrl(String url) throws IOException, InterruptedException {
    URI uri = URI.create(url);
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).build(),
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IOException(uri + " returned " + response.statusCode());
    }
    return response.body();
  }

  private static void write(Path path, String content) throws IOException {
    Files.write(path, content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
  }
}
