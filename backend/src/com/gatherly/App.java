package com.gatherly;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Raw JDK HTTP server + JDBC. Requires the Oracle JDBC driver on the classpath.
 */
public final class App {
  static final Properties cfg = new Properties();
  static final Map<String, Session> sessions = new ConcurrentHashMap<>();
  static Path staticDir;

  public static void main(String[] args) throws Exception {
    // Supports starting the server from either the project root or /backend.
    Path configFile = Files.exists(Paths.get("config.properties"))
        ? Paths.get("config.properties")
        : Paths.get("backend", "config.properties");
    try (InputStream in = Files.newInputStream(configFile)) {
      cfg.load(in);
    }
    // Resolve the public HTML/CSS/JS folder relative to config.properties.
    staticDir = configFile.toAbsolutePath().getParent()
        .resolve(cfg.getProperty("static.dir", ".."))
        .normalize().toRealPath();
    Class.forName("oracle.jdbc.OracleDriver");
    HttpServer server = HttpServer.create(new InetSocketAddress(Integer.parseInt(cfg.getProperty("port", "8080"))), 0);
    server.createContext("/api", App::api);
    server.createContext("/", App::staticFile);
    server.setExecutor(Executors.newFixedThreadPool(12));
    server.start();
    System.out.println("Gatherly running at http://localhost:" + cfg.getProperty("port", "8080"));
  }

  static Connection db() throws SQLException {
    return DriverManager.getConnection(
        cfg.getProperty("db.url"),
        cfg.getProperty("db.user"),
        cfg.getProperty("db.password")
    );
}

  static void api(HttpExchange x) throws IOException {
    try {
      String path = x.getRequestURI().getPath().substring(4), method = x.getRequestMethod();
      if (method.equals("OPTIONS")) {
        send(x, 204, "");
        return;
      }
      if (path.equals("/auth/register") && method.equals("POST")) {
        register(x);
        return;
      }
      if (path.equals("/auth/login") && method.equals("POST")) {
        login(x);
        return;
      }
      // Only an ADMIN can create another ADMIN or EVENT_MANAGER account.
      if (path.equals("/admin/staff") && method.equals("POST")) {
        createStaff(x);
        return;
      }
      if (path.equals("/events") && method.equals("GET")) {
        events(x);
        return;
      }
      if (path.equals("/manage/events") && method.equals("GET")) {
        managedEvents(x);
        return;
      }
      if (path.matches("/events/\\d+") && method.equals("GET")) {
        event(x, id(path));
        return;
      }
      if (path.equals("/events") && method.equals("POST")) {
        saveEvent(x, 0);
        return;
      }
      if (path.matches("/events/\\d+") && (method.equals("PUT") || method.equals("DELETE"))) {
        saveEvent(x, id(path));
        return;
      }
      if (path.equals("/orders") && method.equals("POST")) {
        createOrder(x);
        return;
      }
      if (path.equals("/orders") && method.equals("GET")) {
        userOrders(x);
        return;
      }
      if (path.equals("/payments/sslcommerz") && method.equals("POST")) {
        payment(x);
        return;
      }
      send(x, 404, json("error", "Route not found"));
    } catch (IllegalArgumentException e) {
      // Missing/invalid fields are a client validation issue, not a server crash.
      send(x, 400, json("error", e.getMessage()));
    } catch (SecurityException e) {
      send(x, 403, json("error", e.getMessage()));
    } catch (Exception e) {
      e.printStackTrace();
      send(x, 500, json("error", "Server error"));
    }
  }

  static void register(HttpExchange x) throws Exception {
    Map<String, String> b = body(x);
    String name = req(b, "name"), email = req(b, "email").toLowerCase(), pass = req(b, "password");
    // This is the requested one-time bootstrap Admin email.
    String role = email.equals("s1@gmail.com") ? "ADMIN" : "USER";
    try (Connection c = db();
        PreparedStatement p = c
            .prepareStatement("INSERT INTO gatherlyusers(full_name,email,password_hash,role) VALUES(?,?,?,?)")) {
      p.setString(1, name);
      p.setString(2, email);
      p.setString(3, hash(pass));
      p.setString(4, role);
      p.executeUpdate();
      send(x, 201, json("message", "Account created. Please log in."));
    } catch (SQLIntegrityConstraintViolationException e) {
      send(x, 409, json("error", "This email is already registered."));
    }
  }

  static void login(HttpExchange x) throws Exception {
    Map<String, String> b = body(x);
    try (Connection c = db();
        PreparedStatement p = c
            .prepareStatement("SELECT user_id,full_name,role,password_hash FROM gatherlyusers WHERE email=?")) {
      p.setString(1, req(b, "email").toLowerCase());
      ResultSet r = p.executeQuery();
      if (!r.next() || !hash(req(b, "password")).equals(r.getString("password_hash"))) {
        send(x, 401, json("error", "Invalid email or password."));
        return;
      }
      String email = req(b, "email").toLowerCase();
      String role = r.getString(3);
      // Promote the requested bootstrap account even if it was registered earlier as USER.
      if (email.equals("s1@gmail.com") && !role.equals("ADMIN")) {
        try (PreparedStatement promote = c.prepareStatement("UPDATE gatherlyusers SET role='ADMIN' WHERE user_id=?")) {
          promote.setLong(1, r.getLong(1)); promote.executeUpdate();
        }
        role = "ADMIN";
      }
      String token = UUID.randomUUID().toString();
      sessions.put(token, new Session(r.getLong(1), r.getString(2), role));
      send(x, 200,
          "{\"token\":\"" + token + "\",\"name\":\"" + esc(r.getString(2)) + "\",\"role\":\"" + role + "\"}");
    }
  }

  /** Create a privileged account. This route is intentionally not public. */
  static void createStaff(HttpExchange x) throws Exception {
    auth(x, "ADMIN");
    Map<String, String> b = body(x);
    String role = req(b, "role");
    if (!role.equals("ADMIN") && !role.equals("EVENT_MANAGER")) {
      send(x, 400, json("error", "Role must be ADMIN or EVENT_MANAGER."));
      return;
    }
    try (Connection c = db();
        PreparedStatement p = c.prepareStatement(
            "INSERT INTO gatherlyusers(full_name,email,password_hash,role) VALUES(?,?,?,?)")) {
      p.setString(1, req(b, "name"));
      p.setString(2, req(b, "email").toLowerCase());
      p.setString(3, hash(req(b, "password")));
      p.setString(4, role);
      p.executeUpdate();
      send(x, 201, json("message", role + " account created."));
    } catch (SQLIntegrityConstraintViolationException e) {
      send(x, 409, json("error", "This email is already registered."));
    }
  }

  static void events(HttpExchange x) throws Exception {
    StringBuilder out = new StringBuilder("[");
    try (Connection c = db();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(
            "SELECT event_id,title,description,event_date,venue,address,latitude,longitude,price,image_url,status FROM events WHERE status='PUBLISHED' ORDER BY event_date")) {
      while (r.next()) {
        if (out.length() > 1)
          out.append(',');
        out.append(eventJson(r));
      }
    }
    out.append(']');
    send(x, 200, out.toString());
  }

  static void event(HttpExchange x, long eventId) throws Exception {
    try (Connection c = db();
        PreparedStatement p = c.prepareStatement(
            "SELECT event_id,title,description,event_date,venue,address,latitude,longitude,price,image_url,status FROM events WHERE event_id=?")) {
      p.setLong(1, eventId);
      ResultSet r = p.executeQuery();
      if (!r.next()) {
        send(x, 404, json("error", "Event not found"));
        return;
      }
      send(x, 200, eventJson(r));
    }
  }

  /** Admin sees every event; Event Manager sees only events they created. */
  static void managedEvents(HttpExchange x) throws Exception {
    Session user = auth(x, "ADMIN", "EVENT_MANAGER");
    String sql = user.role.equals("ADMIN")
        ? "SELECT event_id,title,description,event_date,venue,address,latitude,longitude,price,image_url,status FROM events ORDER BY event_date"
        : "SELECT event_id,title,description,event_date,venue,address,latitude,longitude,price,image_url,status FROM events WHERE created_by=? ORDER BY event_date";
    StringBuilder output = new StringBuilder("[");
    try (Connection c = db(); PreparedStatement p = c.prepareStatement(sql)) {
      if (!user.role.equals("ADMIN")) p.setLong(1, user.id);
      ResultSet result = p.executeQuery();
      while (result.next()) { if (output.length() > 1) output.append(','); output.append(eventJson(result)); }
    }
    send(x, 200, output.append(']').toString());
  }

  static void saveEvent(HttpExchange x, long eventId) throws Exception {
    Session u = auth(x, "ADMIN", "EVENT_MANAGER");
    if (eventId != 0) requireEventPermission(eventId, u);
    if (x.getRequestMethod().equals("DELETE")) {
      try (Connection c = db(); PreparedStatement p = c.prepareStatement("DELETE FROM events WHERE event_id=?")) {
        p.setLong(1, eventId);
        p.executeUpdate();
        send(x, 204, "");
      }
      return;
    }
    Map<String, String> b = body(x);
    String sql = eventId == 0
        ? "INSERT INTO events(title,description,event_date,venue,address,latitude,longitude,price,image_url,status,created_by) VALUES(?,?,?,?,?,?,?,?,?, 'PUBLISHED',?)"
        : "UPDATE events SET title=?,description=?,event_date=?,venue=?,address=?,latitude=?,longitude=?,price=?,image_url=? WHERE event_id=?";
    try (Connection c = db(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, req(b, "title"));
      p.setString(2, req(b, "description"));
      p.setTimestamp(3, Timestamp.valueOf(req(b, "eventDate").replace("T", " ") + ":00"));
      p.setString(4, req(b, "venue"));
      p.setString(5, req(b, "address"));
      p.setBigDecimal(6, new java.math.BigDecimal(req(b, "latitude")));
      p.setBigDecimal(7, new java.math.BigDecimal(req(b, "longitude")));
      p.setBigDecimal(8, new java.math.BigDecimal(req(b, "price")));
      p.setString(9, b.getOrDefault("imageUrl", ""));
      p.setLong(10, eventId == 0 ? u.id : eventId);
      p.executeUpdate();
      send(x, eventId == 0 ? 201 : 200, json("message", "Event saved"));
    }
  }

  /** Admin can edit every event; an Event Manager can only edit/delete their own. */
  static void requireEventPermission(long eventId, Session user) throws Exception {
    if (user.role.equals("ADMIN")) return;
    try (Connection c = db(); PreparedStatement p = c.prepareStatement("SELECT created_by FROM events WHERE event_id=?")) {
      p.setLong(1, eventId);
      ResultSet result = p.executeQuery();
      if (!result.next()) throw new IllegalArgumentException("Event not found.");
      if (result.getLong(1) != user.id) throw new SecurityException("You can only manage events you created.");
    }
  }

  static void createOrder(HttpExchange x) throws Exception {
    Session u = auth(x, "USER", "ADMIN", "EVENT_MANAGER");
    Map<String, String> b = body(x);
    long eid = Long.parseLong(req(b, "eventId"));
    int qty = Integer.parseInt(req(b, "quantity"));
    try (Connection c = db();
        PreparedStatement e = c.prepareStatement("SELECT price FROM events WHERE event_id=?");
        PreparedStatement p = c.prepareStatement(
            "INSERT INTO orders(user_id,event_id,quantity,total_amount,status) VALUES(?,?,?,?, 'PENDING')",
            new String[] { "order_id" })) {
      e.setLong(1, eid);
      ResultSet r = e.executeQuery();
      if (!r.next()) {
        send(x, 404, json("error", "Event not found"));
        return;
      }
      java.math.BigDecimal amount = r.getBigDecimal(1).multiply(java.math.BigDecimal.valueOf(qty));
      p.setLong(1, u.id);
      p.setLong(2, eid);
      p.setInt(3, qty);
      p.setBigDecimal(4, amount);
      p.executeUpdate();
      ResultSet keys = p.getGeneratedKeys();
      keys.next();
      send(x, 201, "{\"orderId\":" + keys.getLong(1) + ",\"amount\":" + amount + "}");
    }
  }

  static void payment(HttpExchange x) throws Exception {
    Session u = auth(x, "USER", "ADMIN", "EVENT_MANAGER");
    Map<String, String> b = body(x);
    long oid = Long.parseLong(req(b, "orderId"));
    try (Connection c = db();
        PreparedStatement p = c.prepareStatement("SELECT total_amount FROM orders WHERE order_id=? AND user_id=?")) {
      p.setLong(1, oid);
      p.setLong(2, u.id);
      ResultSet r = p.executeQuery();
      if (!r.next()) {
        send(x, 404, json("error", "Order not found"));
        return;
      }
      String tx = "GAT-" + oid + "-" + System.currentTimeMillis();
      send(x, 200,
          "{\"gateway\":\"" + esc(cfg.getProperty("ssl.gateway")) + "\",\"storeId\":\""
              + esc(cfg.getProperty("ssl.store_id")) + "\",\"transactionId\":\"" + tx + "\",\"amount\":"
              + r.getBigDecimal(1) + ",\"currency\":\"BDT\",\"successUrl\":\""
              + esc(cfg.getProperty("app.url") + "/payment-success") + "\"}");
    }
  }

  /** Returns only the logged-in user's orders for the User dashboard. */
  static void userOrders(HttpExchange x) throws Exception {
    Session u = auth(x, "USER", "ADMIN", "EVENT_MANAGER");
    StringBuilder output = new StringBuilder("[");
    try (Connection c = db(); PreparedStatement p = c.prepareStatement(
        "SELECT o.order_id,o.quantity,o.total_amount,o.status,e.title,e.event_date "
        + "FROM orders o JOIN events e ON e.event_id=o.event_id WHERE o.user_id=? ORDER BY o.created_at DESC")) {
      p.setLong(1, u.id);
      ResultSet r = p.executeQuery();
      while (r.next()) {
        if (output.length() > 1) output.append(',');
        output.append("{\"orderId\":").append(r.getLong(1))
            .append(",\"quantity\":").append(r.getInt(2))
            .append(",\"amount\":").append(r.getBigDecimal(3))
            .append(",\"status\":\"").append(esc(r.getString(4)))
            .append("\",\"title\":\"").append(esc(r.getString(5)))
            .append("\",\"date\":\"").append(r.getTimestamp(6)).append("\"}");
      }
    }
    send(x, 200, output.append(']').toString());
  }

  static Session auth(HttpExchange x, String... roles) {
    String h = x.getRequestHeaders().getFirst("Authorization");
    Session s = h == null ? null : sessions.get(h.replace("Bearer ", ""));
    if (s == null)
      throw new SecurityException("Please log in first.");
    for (String role : roles)
      if (role.equals(s.role))
        return s;
    throw new SecurityException("You do not have permission for this action.");
  }

  static Map<String, String> body(HttpExchange x) throws IOException {
    String raw = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> m = new HashMap<>();
    Matcher a = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|([0-9.]+))").matcher(raw);
    while (a.find())
      m.put(a.group(1), a.group(2) != null ? a.group(2) : a.group(3));
    return m;
  }

  static String req(Map<String, String> b, String k) {
    if (!b.containsKey(k) || b.get(k).isBlank())
      throw new IllegalArgumentException(k + " is required");
    return b.get(k);
  }

  static long id(String p) {
    return Long.parseLong(p.substring(p.lastIndexOf('/') + 1));
  }

  static String hash(String v) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
    StringBuilder s = new StringBuilder();
    for (byte b : d)
      s.append(String.format("%02x", b));
    return s.toString();
  }

  static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  static String json(String k, String v) {
    return "{\"" + k + "\":\"" + esc(v) + "\"}";
  }

  static String eventJson(ResultSet r) throws SQLException {
    return "{\"id\":" + r.getLong(1) + ",\"title\":\"" + esc(r.getString(2)) + "\",\"description\":\""
        + esc(r.getString(3)) + "\",\"date\":\"" + r.getTimestamp(4) + "\",\"venue\":\"" + esc(r.getString(5))
        + "\",\"address\":\"" + esc(r.getString(6)) + "\",\"lat\":" + r.getBigDecimal(7) + ",\"lng\":"
        + r.getBigDecimal(8) + ",\"price\":" + r.getBigDecimal(9) + ",\"imageUrl\":\"" + esc(r.getString(10)) + "\"}";
  }

  static void send(HttpExchange x, int code, String body) throws IOException {
    x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    x.getResponseHeaders().set("Access-Control-Allow-Origin", cfg.getProperty("app.url", "http://localhost:8080"));
    x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    // HTTP 204 must never contain a response body (used for CORS and DELETE).
    if (code == 204) {
      x.sendResponseHeaders(code, -1);
      return;
    }
    x.sendResponseHeaders(code, body.getBytes(StandardCharsets.UTF_8).length);
    try (OutputStream o = x.getResponseBody()) {
      o.write(body.getBytes(StandardCharsets.UTF_8));
    }
  }

  static void staticFile(HttpExchange x) throws IOException {
    String p = x.getRequestURI().getPath();
    if (p.equals("/"))
      p = "/index.html";
    Path f = staticDir.resolve(p.substring(1)).normalize();
    if (!f.startsWith(staticDir) || !Files.exists(f)) {
      x.sendResponseHeaders(404, -1);
      return;
    }
    String type = p.endsWith(".css") ? "text/css" : p.endsWith(".js") ? "application/javascript" : "text/html";
    x.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
    x.getResponseHeaders().set("Cache-Control", "no-store");
    byte[] b = Files.readAllBytes(f);
    x.sendResponseHeaders(200, b.length);
    try (OutputStream o = x.getResponseBody()) {
      o.write(b);
    }
  }

  record Session(long id, String name, String role) {
  }
}
