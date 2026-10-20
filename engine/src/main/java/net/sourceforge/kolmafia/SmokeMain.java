package net.sourceforge.kolmafia;

import java.io.File;
import java.nio.file.Files;

/** JVM smoke test: boots the core in a temp dir and runs some ASH. */
public final class SmokeMain {
  public static void main(String[] args) throws Exception {
    File root = args.length > 0 ? new File(args[0]) : Files.createTempDirectory("kolmob-smoke").toFile();
    System.out.println("root = " + root);
    KolEngine.start(root, line -> System.out.println("[kol] " + line));
    String ua = System.getProperty("smoke.ua");
    if (ua != null) net.sourceforge.kolmafia.request.GenericRequest.setUserAgent(ua);
    System.out.println("User-Agent sent to KoL: " + net.sourceforge.kolmafia.request.GenericRequest.getUserAgent());
    String loginArg = System.getProperty("smoke.login");
    if (loginArg != null) {
      String[] up = loginArg.split(":", 2);
      KolEngine.login(up[0], up[1]);
      System.out.println("loggedIn=" + KolEngine.isLoggedIn());
      String cmds = System.getProperty("smoke.cmds");
      if (cmds != null) for (String c : cmds.split("\\|\\|")) KolEngine.execute(c.trim());
      System.exit(0);
    }
    String only = System.getProperty("smoke.cmd");
    for (String cmd :
        only != null ? only.split("\\|\\|") : new String[] {
          "ash print(\"hello from ASH \" + (1 + 2 * 3));",
          "ash to_item(\"seal tooth\")",
          "ash item_amount($item[seal tooth])",
          "ash int x = 0; for i from 1 to 10 { x += i; } print(\"sum=\" + x);",
          "ash print(to_string(my_name()));",
          "ash record r {int a; string b;}; r x; x.a=3; x.b=\"hi\"; print(x.a + x.b);",
          "ash int[string] m; m[\"a\"]=1; m[\"b\"]=2; foreach k,v in m print(k+\"=\"+v);",
          "ash print(replace_string(\"a-b-c\",\"-\",\"+\")); matcher mm = create_matcher(\"(\\\\d+)\", \"abc123\"); if (mm.find()) print(mm.group(1));",
          "ash foreach it in $items[seal tooth, spices] print(it + \" \" + to_int(it));",
          "ash print(get_property(\"autoSatisfyWithMall\")); set_property(\"kolmobTest\", \"ok\"); print(get_property(\"kolmobTest\"));",
          "ash print($monster[fruit bat].base_attack + \" \" + $location[The Bat Hole Entrance]);",
          "ash print(item_drops($monster[fruit bat]).count());",
          "ash string s = visit_url(\"https://www.kingdomofloathing.com/login.php\", false); print(\"fetched chars: \" + length(s));",
        }) {
      System.out.println("> " + cmd);
      try {
        KolEngine.execute(cmd);
      } catch (Throwable t) {
        System.out.println("!! " + t);
        t.printStackTrace(System.out);
      }
    }
    // Direct check of the HttpURLConnection-based HttpClient shim (Android replacement for java.net.http).
    try {
      var c = com.kolmob.http.HttpClient.newBuilder()
          .followRedirects(com.kolmob.http.HttpClient.Redirect.NEVER).build();
      var rq = com.kolmob.http.HttpRequest.newBuilder(java.net.URI.create("https://www.kingdomofloathing.com/login.php"))
          .header("User-Agent", "Kolmob-smoke").build();
      var rs = c.send(rq, com.kolmob.http.HttpResponse.BodyHandlers.ofString());
      System.out.println("HTTP shim: status=" + rs.statusCode() + " bytes=" + rs.body().length()
          + " set-cookie=" + rs.headers().map().get("Set-Cookie"));
    } catch (Throwable t) {
      System.out.println("HTTP shim failed: " + t);
    }
    System.out.println("SMOKE DONE");
    System.exit(0);
  }
}
