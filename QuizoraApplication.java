
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class QuizoraApplication {
    private static final int PORT = 8080;
    private static final String DATABASE_URL = "jdbc:sqlite:quizora.db";
    
    private static List<User> USERS = new ArrayList<>();
    private static List<Quiz> QUIZZES = new ArrayList<>();
    private static List<QuizAttempt> ATTEMPTS = new ArrayList<>();
    
    private static String successMessage = "";
    private static final ThreadLocal<User> currentUser = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        loadData();
        if (USERS.isEmpty()) {
            seedData();
            saveData();
        }

        Tomcat server = new Tomcat();
        server.setPort(PORT);
        server.getConnector();
        Context context = server.addContext("", new File(".").getAbsolutePath());
        Tomcat.addServlet(context, "quizora", new QuizoraServlet());
        context.addServletMappingDecoded("/", "quizora");
        server.start();
        System.out.println("Quizora is running at http://localhost:" + PORT);
        server.getServer().await();
    }

    /** Front controller Servlet that preserves the application's existing route behavior. */
    public static final class QuizoraServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            handle(request, response);
        }

        @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            handle(request, response);
        }
    }
    
    private static void loadData() {
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE IF NOT EXISTS users (email TEXT PRIMARY KEY, name TEXT NOT NULL, password TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS quizzes (id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL, duration INTEGER NOT NULL, level TEXT NOT NULL, status TEXT NOT NULL, creator_email TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS questions (id INTEGER PRIMARY KEY, quiz_id INTEGER NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE, position INTEGER NOT NULL, text TEXT NOT NULL, option0 TEXT, option1 TEXT, option2 TEXT, option3 TEXT, correct_option INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS attempts (id INTEGER PRIMARY KEY, user_email TEXT NOT NULL, quiz_title TEXT NOT NULL, score INTEGER NOT NULL, max_score INTEGER NOT NULL, date TEXT NOT NULL)");
            try (ResultSet rs = statement.executeQuery("SELECT email, name, password, role, status FROM users ORDER BY rowid")) {
                while (rs.next()) USERS.add(new User(rs.getString("name"), rs.getString("email"), rs.getString("password"), rs.getString("role"), rs.getString("status")));
            }
            try (ResultSet rs = statement.executeQuery("SELECT id, title, description, duration, level, status, creator_email FROM quizzes ORDER BY id")) {
                Map<Integer, Quiz> byId = new HashMap<>();
                while (rs.next()) {
                    Quiz quiz = new Quiz(rs.getString("title"), rs.getString("description"), rs.getInt("duration"), rs.getString("level"), rs.getString("status"), rs.getString("creator_email"));
                    QUIZZES.add(quiz); byId.put(rs.getInt("id"), quiz);
                }
                try (Statement questionStatement = connection.createStatement(); ResultSet qr = questionStatement.executeQuery("SELECT quiz_id, text, option0, option1, option2, option3, correct_option FROM questions ORDER BY quiz_id, position")) {
                    while (qr.next()) {
                        Quiz quiz = byId.get(qr.getInt("quiz_id"));
                        if (quiz != null) quiz.questions.add(new Question(qr.getString("text"), new String[]{qr.getString("option0"), qr.getString("option1"), qr.getString("option2"), qr.getString("option3")}, qr.getInt("correct_option")));
                    }
                }
            }
            try (ResultSet rs = statement.executeQuery("SELECT user_email, quiz_title, score, max_score, date FROM attempts ORDER BY id")) {
                while (rs.next()) ATTEMPTS.add(new QuizAttempt(rs.getString("user_email"), rs.getString("quiz_title"), rs.getInt("score"), rs.getInt("max_score"), rs.getString("date")));
            }
            System.out.println("SQLite database loaded successfully.");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize/load Quizora SQLite database", e);
        }
    }
    
    private static void saveData() {
        try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.executeUpdate("DELETE FROM questions");
                statement.executeUpdate("DELETE FROM quizzes");
                statement.executeUpdate("DELETE FROM users");
                statement.executeUpdate("DELETE FROM attempts");
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO users(email,name,password,role,status) VALUES(?,?,?,?,?)")) {
                    for (User user : USERS) { ps.setString(1,user.email); ps.setString(2,user.name); ps.setString(3,user.password); ps.setString(4,user.role); ps.setString(5,user.status); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement quizPs = connection.prepareStatement("INSERT INTO quizzes(id,title,description,duration,level,status,creator_email) VALUES(?,?,?,?,?,?,?)");
                     PreparedStatement questionPs = connection.prepareStatement("INSERT INTO questions(quiz_id,position,text,option0,option1,option2,option3,correct_option) VALUES(?,?,?,?,?,?,?,?)")) {
                    int quizId = 0;
                    for (Quiz quiz : QUIZZES) {
                        quizId++;
                        quizPs.setInt(1,quizId); quizPs.setString(2,quiz.title); quizPs.setString(3,quiz.description); quizPs.setInt(4,quiz.duration); quizPs.setString(5,quiz.level); quizPs.setString(6,quiz.status); quizPs.setString(7,quiz.creatorEmail); quizPs.executeUpdate();
                        for (int i = 0; i < quiz.questions.size(); i++) {
                            Question q = quiz.questions.get(i); questionPs.setInt(1,quizId); questionPs.setInt(2,i); questionPs.setString(3,q.text);
                            for (int j = 0; j < 4; j++) questionPs.setString(4+j, j < q.options.length ? q.options[j] : "");
                            questionPs.setInt(8,q.correctOptionIndex); questionPs.addBatch();
                        }
                    }
                    questionPs.executeBatch();
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO attempts(user_email,quiz_title,score,max_score,date) VALUES(?,?,?,?,?)")) {
                    for (QuizAttempt a : ATTEMPTS) { ps.setString(1,a.userEmail); ps.setString(2,a.quizTitle); ps.setInt(3,a.score); ps.setInt(4,a.maxScore); ps.setString(5,a.date); ps.addBatch(); }
                    ps.executeBatch();
                }
            }
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Could not save Quizora data to SQLite", e);
        }
    }

    private static void seedData() {
        USERS.add(new User("Shushant", "shushant@quizora.dev", "12345", "Admin", "Active"));

        // ── 1. Java Fundamentals ──────────────────────────────────────────
        Quiz q1 = new Quiz("Java Fundamentals", "Core concepts: variables, data types, operators, and control flow.", 20, "Beginner", "Published", "shushant@quizora.dev");
        q1.questions.add(new Question("Which keyword is used to inherit a class in Java?", new String[]{"implement", "extends", "inherits", "super"}, 1));
        q1.questions.add(new Question("What is the size of an int in Java?", new String[]{"8 bit", "16 bit", "32 bit", "64 bit"}, 2));
        q1.questions.add(new Question("Which of these is NOT a primitive data type in Java?", new String[]{"int", "boolean", "String", "char"}, 2));
        q1.questions.add(new Question("What does the 'final' keyword do to a variable?", new String[]{"Makes it static", "Makes it constant", "Makes it global", "Makes it private"}, 1));
        q1.questions.add(new Question("Which loop is guaranteed to execute at least once?", new String[]{"for", "while", "do-while", "foreach"}, 2));
        QUIZZES.add(q1);

        // ── 2. Object-Oriented Programming ───────────────────────────────
        Quiz q2 = new Quiz("Object-Oriented Programming", "Dive into classes, objects, inheritance, polymorphism, and encapsulation.", 25, "Intermediate", "Published", "shushant@quizora.dev");
        q2.questions.add(new Question("Which OOP concept allows a class to have multiple methods with the same name?", new String[]{"Overriding", "Overloading", "Encapsulation", "Abstraction"}, 1));
        q2.questions.add(new Question("Can a Java class directly inherit from multiple classes?", new String[]{"Yes", "No", "Only abstract classes", "Only interfaces"}, 1));
        q2.questions.add(new Question("Which keyword is used to call the parent class constructor?", new String[]{"this()", "super()", "parent()", "base()"}, 1));
        q2.questions.add(new Question("What is encapsulation?", new String[]{"Hiding implementation details", "Inheriting properties", "Overriding methods", "Creating objects"}, 0));
        q2.questions.add(new Question("An abstract class can be instantiated directly.", new String[]{"True", "False", "Only if it has a constructor", "Depends on the JVM"}, 1));
        QUIZZES.add(q2);

        // ── 3. Data Structures ───────────────────────────────────────────
        Quiz q3 = new Quiz("Data Structures", "Arrays, LinkedLists, Stacks, Queues, Trees, and more.", 30, "Intermediate", "Published", "shushant@quizora.dev");
        q3.questions.add(new Question("Which data structure uses LIFO order?", new String[]{"Queue", "Stack", "Array", "LinkedList"}, 1));
        q3.questions.add(new Question("What is the time complexity of binary search?", new String[]{"O(n)", "O(n^2)", "O(log n)", "O(1)"}, 2));
        q3.questions.add(new Question("Which data structure is used for BFS traversal?", new String[]{"Stack", "Queue", "Tree", "Graph"}, 1));
        q3.questions.add(new Question("A HashMap in Java stores data as:", new String[]{"Sorted key-value pairs", "Key-value pairs", "Only keys", "Only values"}, 1));
        q3.questions.add(new Question("Which sorting algorithm has the best average-case complexity?", new String[]{"Bubble Sort", "Selection Sort", "Quick Sort", "Insertion Sort"}, 2));
        QUIZZES.add(q3);

        // ── 4. SQL & Databases ───────────────────────────────────────────
        Quiz q4 = new Quiz("SQL and Databases", "SQL queries, joins, indexes, and database design fundamentals.", 25, "Beginner", "Published", "shushant@quizora.dev");
        q4.questions.add(new Question("Which SQL clause is used to filter rows?", new String[]{"ORDER BY", "GROUP BY", "WHERE", "HAVING"}, 2));
        q4.questions.add(new Question("What does SELECT DISTINCT do?", new String[]{"Sorts results", "Returns only unique values", "Counts rows", "Deletes duplicates"}, 1));
        q4.questions.add(new Question("Which JOIN returns all rows from both tables?", new String[]{"INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL OUTER JOIN"}, 3));
        q4.questions.add(new Question("What is a PRIMARY KEY?", new String[]{"A key that can be NULL", "A unique identifier for a row", "A foreign reference", "An index key"}, 1));
        q4.questions.add(new Question("Which command removes all rows from a table without deleting the table?", new String[]{"DELETE", "DROP", "TRUNCATE", "REMOVE"}, 2));
        QUIZZES.add(q4);

        // ── 5. Web Development Basics ────────────────────────────────────
        Quiz q5 = new Quiz("Web Development Basics", "HTML, CSS, HTTP, and how the web works.", 20, "Beginner", "Published", "shushant@quizora.dev");
        q5.questions.add(new Question("What does HTML stand for?", new String[]{"Hyper Text Markup Language", "High Tech Modern Language", "Hyper Transfer Markup Language", "Home Tool Markup Language"}, 0));
        q5.questions.add(new Question("Which HTML tag is used to link a CSS stylesheet?", new String[]{"<style>", "<script>", "<link>", "<css>"}, 2));
        q5.questions.add(new Question("What HTTP status code means 'Not Found'?", new String[]{"200", "301", "403", "404"}, 3));
        q5.questions.add(new Question("Which CSS property changes text colour?", new String[]{"font-color", "text-color", "color", "foreground"}, 2));
        q5.questions.add(new Question("What does API stand for?", new String[]{"Application Programming Interface", "Automated Program Interaction", "Applied Process Integration", "Application Process Input"}, 0));
        QUIZZES.add(q5);

        // ── 6. Python Basics ─────────────────────────────────────────────
        Quiz q6 = new Quiz("Python Basics", "Variables, loops, functions, and core Python syntax.", 20, "Beginner", "Published", "shushant@quizora.dev");
        q6.questions.add(new Question("How do you declare a function in Python?", new String[]{"function myFunc():", "def myFunc():", "func myFunc():", "void myFunc():"}, 1));
        q6.questions.add(new Question("Which symbol is used for single-line comments in Python?", new String[]{"//", "/*", "#", "--"}, 2));
        q6.questions.add(new Question("What is the output of: print(type(3.14))?", new String[]{"<class int>", "<class float>", "<class double>", "<class number>"}, 1));
        q6.questions.add(new Question("Which data structure does NOT allow duplicates in Python?", new String[]{"list", "tuple", "set", "dict"}, 2));
        q6.questions.add(new Question("What does len([1, 2, 3]) return?", new String[]{"2", "3", "4", "Error"}, 1));
        QUIZZES.add(q6);

        // ── 7. Computer Networks ─────────────────────────────────────────
        Quiz q7 = new Quiz("Computer Networks", "OSI model, TCP/IP, protocols, and network fundamentals.", 25, "Intermediate", "Published", "shushant@quizora.dev");
        q7.questions.add(new Question("How many layers does the OSI model have?", new String[]{"5", "6", "7", "8"}, 2));
        q7.questions.add(new Question("Which protocol is used to assign IP addresses automatically?", new String[]{"DNS", "DHCP", "FTP", "SMTP"}, 1));
        q7.questions.add(new Question("What does DNS stand for?", new String[]{"Data Network System", "Domain Name System", "Dynamic Name Server", "Digital Network Service"}, 1));
        q7.questions.add(new Question("Which layer of the OSI model handles routing?", new String[]{"Transport", "Data Link", "Network", "Session"}, 2));
        q7.questions.add(new Question("Which protocol is used to send emails?", new String[]{"HTTP", "FTP", "SMTP", "POP3"}, 2));
        QUIZZES.add(q7);

        // ── 8. General Computer Science ──────────────────────────────────
        Quiz q8 = new Quiz("General Computer Science", "Broad CS knowledge: binary, logic gates, hardware, and OS basics.", 20, "Beginner", "Published", "shushant@quizora.dev");
        q8.questions.add(new Question("What is the binary representation of decimal 10?", new String[]{"1010", "1100", "1001", "1110"}, 0));
        q8.questions.add(new Question("Which gate outputs 1 only when both inputs are 1?", new String[]{"OR", "AND", "NOT", "XOR"}, 1));
        q8.questions.add(new Question("What does CPU stand for?", new String[]{"Central Processing Unit", "Core Processing Unit", "Central Program Utility", "Compute Processing Unit"}, 0));
        q8.questions.add(new Question("Which of these is an example of an operating system?", new String[]{"Google Chrome", "Microsoft Word", "Ubuntu", "Adobe Photoshop"}, 2));
        q8.questions.add(new Question("What is the full form of RAM?", new String[]{"Read Access Memory", "Random Access Memory", "Rapid Access Module", "Read And Memory"}, 1));
        QUIZZES.add(q8);

        // ── 9. Advanced Java ─────────────────────────────────────────────
        Quiz q9 = new Quiz("Advanced Java", "Streams, generics, concurrency, and modern Java features.", 35, "Advanced", "Published", "shushant@quizora.dev");
        q9.questions.add(new Question("Which Java interface is used to create threads that return a result?", new String[]{"Runnable", "Thread", "Callable", "Future"}, 2));
        q9.questions.add(new Question("What does the Stream.filter() method return?", new String[]{"A single element", "A new Stream", "A List", "void"}, 1));
        q9.questions.add(new Question("Which keyword prevents a method from being overridden?", new String[]{"static", "abstract", "final", "private"}, 2));
        q9.questions.add(new Question("What is autoboxing in Java?", new String[]{"Converting int to String", "Automatic conversion between primitives and wrappers", "Allocating memory automatically", "Garbage collection trigger"}, 1));
        q9.questions.add(new Question("Which collection maintains insertion order and allows duplicates?", new String[]{"HashSet", "TreeSet", "ArrayList", "HashMap"}, 2));
        QUIZZES.add(q9);
    }

    private static void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.isEmpty()) path = "/";
        Map<String, String> form = readParameters(request);

        HttpSession session = request.getSession(false);
        String email = session == null ? null : (String) session.getAttribute("userEmail");
        User user = email == null ? null : USERS.stream().filter(u -> u.email.equals(email)).findFirst().orElse(null);
        currentUser.set(user);
        
        try {
            if (path.equals("/action")) {
                performAction(form);
                redirect(response, form.getOrDefault("return", "/"));
                return;
            }
            if (path.equals("/login_submit")) {
                String loginEmail = form.get("email");
                String pass = form.get("password");
                User loginUser = USERS.stream().filter(u -> u.email.equals(loginEmail) && u.password.equals(pass)).findFirst().orElse(null);
                if (loginUser != null) {
                    if (session != null) session.invalidate();
                    request.getSession(true).setAttribute("userEmail", loginUser.email);
                    redirect(response, loginUser.role.equals("Admin") ? "/admin" : loginUser.role.equals("Quiz Creator") ? "/creator" : "/participant");
                } else {
                    send(response, 200, authPage("Invalid email or password", null));
                }
                return;
            }
            if (path.equals("/register_submit")) {
                String regName  = safe(form.get("name"));
                String regEmail = form.get("email");
                String regPass  = form.get("password");
                if (regName.isBlank() || regEmail == null || regEmail.isBlank() || regPass == null || regPass.isBlank()) {
                    send(response, 200, authPage(null, "All fields are required."));
                    return;
                }
                boolean exists = USERS.stream().anyMatch(u -> u.email.equalsIgnoreCase(regEmail));
                if (exists) {
                    send(response, 200, authPage(null, "An account with that email already exists."));
                    return;
                }
                USERS.add(new User(regName, regEmail, regPass, "Participant", "Active"));
                saveData();
                if (session != null) session.invalidate();
                request.getSession(true).setAttribute("userEmail", regEmail);
                redirect(response, "/participant");
                return;
            }
            if (path.equals("/logout")) {
                if (session != null) session.invalidate();
                redirect(response, "/auth");
                return;
            }

            if (user == null && !path.equals("/auth") && !path.equals("/") && !path.equals("/favicon.ico")) {
                redirect(response, "/auth");
                return;
            }

            String html = switch (path) {
                case "/", "/auth" -> authPage(null, null);
                case "/participant" -> participantDashboard();
                case "/quizzes" -> quizLibrary(form.getOrDefault("search", ""));
                case "/quiz" -> takeQuiz(form.getOrDefault("name", ""));
                case "/submit" -> submitQuiz(form);
                case "/performance" -> performancePage();
                case "/leaderboard" -> leaderboardPage();
                case "/creator" -> creatorDashboard();
                case "/admin" -> adminDashboard();
                default -> notFound();
            };
            if (path.equals("/favicon.ico")) { response.setStatus(HttpServletResponse.SC_NO_CONTENT); return; }
            send(response, 200, html);
        } finally {
            currentUser.remove();
        }
    }

    private static Map<String, String> readParameters(HttpServletRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, value) -> values.put(key, value == null || value.length == 0 ? "" : value[0]));
        return values;
    }

    private static void performAction(Map<String, String> form) {
        String action = form.getOrDefault("type", "");
        switch (action) {
            case "addUser" -> {
                USERS.add(new User(safe(form.get("name")), safe(form.get("email")), "password", safe(form.get("role")), "Active"));
                successMessage = "User account created successfully.";
            }
            case "deleteUser" -> {
                USERS.removeIf(u -> u.email.equals(form.get("email")));
                successMessage = "User account deleted successfully.";
            }
            case "approveQuiz" -> {
                QUIZZES.stream().filter(q -> q.title.equals(form.get("name"))).findFirst().ifPresent(q -> q.status = "Published");
                successMessage = "Quiz approved and published.";
            }
            case "rejectQuiz" -> {
                QUIZZES.stream().filter(q -> q.title.equals(form.get("name"))).findFirst().ifPresent(q -> q.status = "Rejected");
                successMessage = "Quiz submission rejected.";
            }
            case "createQuiz" -> {
                int duration = number(form.get("duration"), 15);
                Quiz newQuiz = new Quiz(safe(form.get("title")), safe(form.get("description")), duration, safe(form.get("level")), "Pending", currentUser.get().email);
                
                // Read questions from form dynamically
                for (int i = 0; i < 50; i++) {
                    String qText = form.get("q" + i + "_text");
                    if (qText != null && !qText.isBlank()) {
                        String[] opts = {
                            safe(form.get("q" + i + "_o0")),
                            safe(form.get("q" + i + "_o1")),
                            safe(form.get("q" + i + "_o2")),
                            safe(form.get("q" + i + "_o3"))
                        };
                        int correct = number(form.get("q" + i + "_correct"), 0);
                        newQuiz.questions.add(new Question(safe(qText), opts, correct));
                    }
                }
                if (newQuiz.questions.isEmpty()) {
                    newQuiz.questions.add(new Question("Sample Question?", new String[]{"A", "B", "C", "D"}, 0));
                }
                QUIZZES.add(newQuiz);
                successMessage = "Quiz submitted for administrator approval.";
            }
            case "settings" -> successMessage = "System settings updated successfully.";
            default -> successMessage = "Action completed successfully.";
        }
        saveData();
    }

    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    
    private static String authPage(String loginError, String regError) {
        String loginErr = loginError != null ? "<div style='color:#d75a72;background:#ffe7eb;border-radius:8px;padding:10px;margin-bottom:14px;font-size:12px;font-weight:bold'>⚠ " + loginError + "</div>" : "";
        String regErr   = regError   != null ? "<div style='color:#d75a72;background:#ffe7eb;border-radius:8px;padding:10px;margin-bottom:14px;font-size:12px;font-weight:bold'>⚠ " + regError   + "</div>" : "";
        // Decide which tab is active: if there's a reg error, open signup tab
        String loginDisplay = regError != null ? "none" : "block";
        String regDisplay   = regError != null ? "block" : "none";
        String loginTabStyle = regError != null ? "tab-btn" : "tab-btn active";
        String regTabStyle   = regError != null ? "tab-btn active" : "tab-btn";
        return """
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Quizora · Welcome</title>
            <style>%s
            .tab-btn{flex:1;padding:10px;border:none;background:#f4f0fb;color:#766e82;font-weight:700;font-size:12px;cursor:pointer;transition:.2s}
            .tab-btn:first-child{border-radius:10px 0 0 10px}
            .tab-btn:last-child{border-radius:0 10px 10px 0}
            .tab-btn.active{background:var(--purple);color:#fff}
            .tabs{display:flex;margin-bottom:22px;border-radius:10px;overflow:hidden;border:1px solid var(--line)}
            </style></head>
            <body style="display:grid;place-items:center;min-height:100vh;margin:0;background:var(--bg)">
            <section class="panel" style="width:100%%;max-width:420px;text-align:center">
              <div class="brand" style="justify-content:center;margin-bottom:22px;font-size:28px"><span>&lt;/&gt;</span>quiz<b>ora</b></div>
              <div class="tabs">
                <button class="%s" onclick="show('login')">Sign In</button>
                <button class="%s" onclick="show('signup')">Create Account</button>
              </div>

              <!-- LOGIN TAB -->
              <div id="tab-login" style="display:%s">
                %s
                <form class="form" method="post" action="/login_submit">
                  <label style="text-align:left">Email<input type="email" name="email" required placeholder="Enter your email"></label>
                  <label style="text-align:left">Password<input type="password" name="password" required placeholder="Enter your password"></label>
                  <button class="button" style="width:100%%;margin-top:18px" type="submit">Sign In →</button>
                </form>
                <p style="margin-top:14px;font-size:11px;color:#948d9e">Don't have an account? <a href="#" onclick="show('signup')" style="color:var(--purple);font-weight:700">Create one free</a></p>
              </div>

              <!-- SIGN UP TAB -->
              <div id="tab-signup" style="display:%s">
                <p style="font-size:12px;color:#948d9e;margin-bottom:16px">Join Quizora free — no admin needed.<br>You'll get instant access as a Participant.</p>
                %s
                <form class="form" method="post" action="/register_submit">
                  <label style="text-align:left">Full Name<input type="text" name="name" required placeholder="e.g. Rahul Kumar"></label>
                  <label style="text-align:left">Email<input type="email" name="email" required placeholder="e.g. rahul@email.com"></label>
                  <label style="text-align:left">Password<input type="password" name="password" required placeholder="Choose a password"></label>
                  <button class="button" style="width:100%%;margin-top:18px" type="submit">Create Account →</button>
                </form>
                <p style="margin-top:14px;font-size:11px;color:#948d9e">Already have an account? <a href="#" onclick="show('login')" style="color:var(--purple);font-weight:700">Sign in</a></p>
              </div>
            </section>
            <script>
            function show(tab){
              document.getElementById('tab-login').style.display  = tab==='login'  ? 'block' : 'none';
              document.getElementById('tab-signup').style.display = tab==='signup' ? 'block' : 'none';
              document.querySelectorAll('.tab-btn').forEach((b,i)=>b.classList.toggle('active',(tab==='login'?i===0:i===1)));
            }
            </script>
            </body></html>
            """.formatted(CSS, loginTabStyle, regTabStyle, loginDisplay, loginErr, regDisplay, regErr);
    }

    private static String participantDashboard() {
        User user = currentUser.get();
        List<QuizAttempt> myAttempts = ATTEMPTS.stream().filter(a -> a.userEmail.equals(user.email)).collect(Collectors.toList());
        int totalTaken = myAttempts.size();
        double avgScore = myAttempts.isEmpty() ? 0 : myAttempts.stream().mapToDouble(a -> (double)a.score / a.maxScore).average().orElse(0.0) * 100;
        int totalScore = myAttempts.stream().mapToInt(a -> a.score * 10).sum();
        
        String quizCards = QUIZZES.stream().filter(q -> q.status.equals("Published")).limit(3).map(QuizoraApplication::quizCard).reduce("", String::concat);
        return page("Dashboard", "participant", """
            <section class="hero">
              <div><span class="kicker">✦ KEEP LEARNING, KEEP GROWING</span><h1>Welcome, %s! 👋</h1>
              <p>Ready to sharpen your Java skills? Pick up where you left off or explore something new.</p>
              </div>
              <div class="hero-art"><div class="code-window"><i></i><i></i><i></i><code><b>public class</b> Quiz {<br>&nbsp;&nbsp;keepLearning();<br>}</code></div><div class="trophy">🏆</div></div>
            </section>
            <section class="stats">
              <article><span class="stat-icon purple">▤</span><div><small>QUIZZES COMPLETED</small><strong>%d</strong></div></article>
              <article><span class="stat-icon orange">◎</span><div><small>AVERAGE SCORE</small><strong>%.1f%%</strong></div></article>
              <article><span class="stat-icon blue">◷</span><div><small>TOTAL POINTS</small><strong>%d</strong></div></article>
              <article><span class="stat-icon pink">♛</span><div><small>GLOBAL RANK</small><strong>#1</strong></div></article>
            </section>
            <div class="main-grid"><section><div class="section-head"><div><span class="kicker">RECOMMENDED FOR YOU</span><h2>Explore popular quizzes</h2></div><a href="/quizzes">Explore all →</a></div><div class="quiz-grid">%s</div></section>
              <aside class="rank-panel"><span class="kicker">♛ YOUR WEEKLY RANK</span><h3>Top learners</h3><a href="/leaderboard">View full leaderboard →</a></aside>
            </div>
            <section class="history"><div class="section-head"><div><span class="kicker">RECENT ACTIVITY</span><h2>Your quiz history</h2></div><a href="/performance">View full history →</a></div>%s</section>
            """.formatted(user.name.split(" ")[0], totalTaken, avgScore, totalScore, quizCards, historyTable(myAttempts)));
    }

    private static String quizLibrary(String search) {
        String query = search.toLowerCase();
        String cards = QUIZZES.stream().filter(q -> q.status.equals("Published") && q.title.toLowerCase().contains(query)).map(QuizoraApplication::quizCard).reduce("", String::concat);
        return page("Explore Quizzes", "participant", """
            <section class="page-title"><span class="kicker">QUIZ LIBRARY</span><h1>Explore Java quizzes</h1><p>Choose a topic, challenge yourself, and grow your Java expertise.</p></section>
            <form class="search" method="get" action="/quizzes"><span>⌕</span><input name="search" value="%s" placeholder="Search quizzes by topic..."><button>Search</button></form>
            <section class="quiz-grid library">%s</section>
            """.formatted(safe(search), cards.isEmpty() ? "<div class='empty'>No quizzes match your search.</div>" : cards));
    }

    private static String quizCard(Quiz quiz) {
        String color = quiz.level.equals("Beginner") ? "green" : quiz.level.equals("Advanced") ? "pink" : "amber";
        return """
            <article class="quiz-card"><div class="quiz-top"><span class="quiz-icon">&lt;/&gt;</span><span class="pill %s">%s</span></div><h3>%s</h3><p>%s</p><div class="quiz-meta"><span>▤ %d questions</span><span>◷ %d min</span></div><a href="/quiz?name=%s">Start quiz →</a></article>
            """.formatted(color, quiz.level, quiz.title, quiz.description, quiz.questions.size(), quiz.duration, URLEncoder.encode(quiz.title, StandardCharsets.UTF_8));
    }

    private static String takeQuiz(String name) {
        Quiz quiz = QUIZZES.stream().filter(q -> q.title.equals(name)).findFirst().orElse(null);
        if (quiz == null) return notFound();
        
        StringBuilder questionsHtml = new StringBuilder();
        for (int i = 0; i < quiz.questions.size(); i++) {
            Question q = quiz.questions.get(i);
            questionsHtml.append("<h3>").append((i+1)).append(". ").append(q.text).append("</h3>");
            for (int j = 0; j < q.options.length; j++) {
                char letter = (char) ('A' + j);
                questionsHtml.append("<label class='answer'><input type='radio' name='q").append(i).append("' value='").append(j).append("' required><span>").append(letter).append("</span><b>").append(q.options[j]).append("</b></label>");
            }
        }
        
        return page("Take Quiz", "participant", """
            <section class="quiz-room"><header><div><span class="kicker">JAVA QUIZ</span><h2>%s</h2></div><div class="timer">◷ <b>%d:00</b><small>TIME LIMIT</small></div></header>
            <form method="post" action="/submit">
              %s
              <input type="hidden" name="quiz" value="%s"><div class="quiz-actions"><a href="/quizzes">Exit quiz</a><button class="button">Submit Answer →</button></div>
            </form></section>
            """.formatted(safe(name), quiz.duration, questionsHtml, safe(name)));
    }

    private static String submitQuiz(Map<String, String> form) {
        String quizName = form.get("quiz");
        Quiz quiz = QUIZZES.stream().filter(q -> q.title.equals(quizName)).findFirst().orElse(null);
        if (quiz == null) return notFound();
        
        int score = 0;
        for (int i = 0; i < quiz.questions.size(); i++) {
            int selected = number(form.get("q" + i), -1);
            if (selected == quiz.questions.get(i).correctOptionIndex) {
                score++;
            }
        }
        
        QuizAttempt attempt = new QuizAttempt(currentUser.get().email, quiz.title, score, quiz.questions.size(), LocalDate.now().toString());
        ATTEMPTS.add(attempt);
        saveData();
        
        double percentage = (double)score / quiz.questions.size() * 100;
        boolean passed = percentage >= 50;
        
        return page("Quiz Result", "participant", """
            <section class="result-card"><div class="result-ring" style="border-color:%s">%d%%</div><span class="kicker">QUIZ COMPLETE</span><h1>%s</h1><p>You completed <b>%s</b>.</p><div class="result-stats"><div><strong>%d / %d</strong><small>Correct answers</small></div><div><strong>+%d</strong><small>Points earned</small></div></div><a class="button" href="/quizzes">Explore more quizzes</a></section>
            """.formatted(passed ? "#439d68" : "#d75a72", (int)percentage, passed ? "Excellent work!" : "Keep practising!", safe(quizName), score, quiz.questions.size(), score * 10));
    }

    private static String performancePage() {
        User user = currentUser.get();
        List<QuizAttempt> myAttempts = ATTEMPTS.stream().filter(a -> a.userEmail.equals(user.email)).collect(Collectors.toList());
        double avgScore = myAttempts.isEmpty() ? 0 : myAttempts.stream().mapToDouble(a -> (double)a.score / a.maxScore).average().orElse(0.0) * 100;

        return page("Performance", "participant", """
            <section class="page-title"><span class="kicker">PERFORMANCE REPORT</span><h1>Your learning progress</h1></section>
            <section class="performance-hero"><div><small>OVERALL PERFORMANCE</small><strong>%.1f%%</strong><span>Average score</span></div></section>
            %s
            """.formatted(avgScore, historyTable(myAttempts)));
    }

    private static String historyTable(List<QuizAttempt> attempts) {
        if (attempts.isEmpty()) return "<p>No attempts yet.</p>";
        StringBuilder rows = new StringBuilder("<div class='table'><div class='tr th'><span>QUIZ</span><span>DATE</span><span>SCORE</span><span>RESULT</span><span>STATUS</span></div>");
        for (QuizAttempt a : attempts) {
            int percent = (int)((double)a.score / a.maxScore * 100);
            boolean passed = percent >= 50;
            rows.append("<div class='tr'><strong>").append(a.quizTitle).append("</strong><span>").append(a.date).append("</span><b>").append(percent).append("%</b><span>").append(a.score).append(" / ").append(a.maxScore).append("</span><mark style='").append(passed?"":"color:#d75a72;background:#ffe7eb").append("'>").append(passed ? "✓ Passed" : "✗ Failed").append("</mark></div>");
        }
        rows.append("</div>");
        return rows.toString();
    }

    private static String leaderboardPage() {
        Map<String, Integer> scores = new HashMap<>();
        for (QuizAttempt a : ATTEMPTS) {
            scores.put(a.userEmail, scores.getOrDefault(a.userEmail, 0) + (a.score * 10));
        }
        
        List<Map.Entry<String, Integer>> sorted = scores.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).collect(Collectors.toList());
        
        StringBuilder rows = new StringBuilder();
        int rank = 1;
        for (Map.Entry<String, Integer> entry : sorted) {
            User u = USERS.stream().filter(user -> user.email.equals(entry.getKey())).findFirst().orElse(null);
            if (u != null) {
                boolean isMe = u.email.equals(currentUser.get().email);
                rows.append("<div class='leader-row ").append(isMe ? "current" : "").append("'><b>").append(rank++).append("</b><span class='avatar'>").append(initials(u.name)).append("</span><strong>").append(u.name).append(isMe ? " <small>(You)</small>" : "").append("</strong><em>").append(entry.getValue()).append(" pts</em></div>");
            }
        }
        return page("Leaderboard", "participant", "<section class='page-title'><span class='kicker'>COMMUNITY</span><h1>Java leaderboard</h1></section><section class='leaderboard'>" + rows + "</section>");
    }

    private static String creatorDashboard() {
        User user = currentUser.get();
        List<Quiz> myQuizzes = QUIZZES.stream().filter(q -> user.email.equals(q.creatorEmail)).collect(Collectors.toList());
        
        StringBuilder quizRows = new StringBuilder();
        for (Quiz q : myQuizzes) quizRows.append("<div class='tr creator-row'><strong>").append(q.title).append("</strong><span>").append(q.questions.size()).append(" questions</span><span>").append(q.duration).append(" min</span><span class='status ").append(q.status.toLowerCase()).append("'>").append(q.status).append("</span><a href='/quizzes'>View in library →</a></div>");
        
        return page("Creator Studio", "creator", """
            <section class="dashboard-title"><div><span class="kicker">QUIZ CREATOR WORKSPACE</span><h1>Welcome back, %s</h1></div><a class="button" href="#create">＋ Create new quiz</a></section>
            <section class="stats three"><article><span class="stat-icon purple">▤</span><div><small>YOUR QUIZZES</small><strong>%d</strong></div></article></section>
            <section class="panel"><div class="section-head"><div><span class="kicker">CONTENT MANAGEMENT</span><h2>Your quizzes</h2></div></div><div class="table creator-table"><div class="tr th creator-row"><span>QUIZ</span><span>QUESTIONS</span><span>DURATION</span><span>STATUS</span><span>ACTION</span></div>%s</div></section>
            
            <section class="panel" id="create" style="margin-top:20px"><span class="kicker">CREATE QUIZ</span><h2>Build a new Java quiz</h2>
            <form method="post" action="/action" class="form"><input type="hidden" name="type" value="createQuiz"><input type="hidden" name="return" value="/creator">
            <label>Quiz title<input required name="title" placeholder="e.g. Java Streams"></label>
            <label>Description<textarea required name="description" placeholder="What will participants learn?"></textarea></label>
            <div class="form-row"><label>Duration (minutes)<input required type="number" min="1" name="duration" value="15"></label><label>Difficulty<select name="level"><option>Beginner</option><option>Intermediate</option><option>Advanced</option></select></label></div>
            <h3 style="margin-top:20px">Add Questions</h3>
            <div id="questions-container">
               <div style="border:1px solid #ddd; padding:10px; border-radius:8px; margin-bottom:10px">
                  <label>Question 1 text: <input required name="q0_text"></label>
                  <label>Option A: <input required name="q0_o0"></label>
                  <label>Option B: <input required name="q0_o1"></label>
                  <label>Option C: <input required name="q0_o2"></label>
                  <label>Option D: <input required name="q0_o3"></label>
                  <label>Correct Option Index (0-3): <input required type="number" min="0" max="3" name="q0_correct" value="0"></label>
               </div>
            </div>
            <button class="button">Submit for approval</button></form></section>
            """.formatted(user.name.split(" ")[0], myQuizzes.size(), quizRows));
    }

    private static String adminDashboard() {
        StringBuilder users = new StringBuilder();
        for (User u : USERS) users.append("<div class='tr admin-user'><strong>").append(u.name).append("<small>").append(u.email).append("</small></strong><span>").append(u.role).append("</span><mark>● ").append(u.status).append("</mark><form method='post' action='/action'><input type='hidden' name='type' value='deleteUser'><input type='hidden' name='return' value='/admin'><input type='hidden' name='email' value='").append(u.email).append("'><button class='danger'>Delete</button></form></div>");
        StringBuilder pending = new StringBuilder();
        for (Quiz q : QUIZZES) pending.append("<div class='approval'><div><strong>").append(q.title).append("</strong><small>").append(q.level).append(" · ").append(q.questions.size()).append(" questions · ").append(q.duration).append(" min</small></div><span class='status ").append(q.status.toLowerCase()).append("'>").append(q.status).append("</span><form method='post' action='/action'><input type='hidden' name='return' value='/admin'><input type='hidden' name='name' value='").append(q.title).append("'><button name='type' value='approveQuiz'>Approve</button><button class='danger' name='type' value='rejectQuiz'>Reject</button></form></div>");
        return page("Admin Console", "admin", """
            <section class="dashboard-title"><div><span class="kicker">ADMINISTRATION</span><h1>System overview</h1></div><span class="health">● All systems operational</span></section>
            <section class="stats"><article><span class="stat-icon purple">♟</span><div><small>TOTAL USERS</small><strong>%d</strong></div></article><article><span class="stat-icon orange">▤</span><div><small>ALL QUIZZES</small><strong>%d</strong></div></article><article><span class="stat-icon blue">▶</span><div><small>TOTAL ATTEMPTS</small><strong>%d</strong></div></article></section>
            <div class="admin-grid"><section class="panel"><div class="section-head"><div><span class="kicker">USER MANAGEMENT</span><h2>User accounts</h2></div></div><div class="table"><div class="tr th admin-user"><span>USER</span><span>ROLE</span><span>STATUS</span><span>ACTION</span></div>%s</div><details><summary>＋ Add new user</summary><form method="post" action="/action" class="form inline-form"><input type="hidden" name="type" value="addUser"><input type="hidden" name="return" value="/admin"><label>Name<input required name="name"></label><label>Email<input required type="email" name="email"></label><label>Role<select name="role"><option>Participant</option><option>Quiz Creator</option><option>Admin</option></select></label><button class="button">Create user</button></form></details></section>
            </div>
            <section class="panel approvals"><div class="section-head"><div><span class="kicker">QUIZ CONTENT MANAGEMENT</span><h2>Content approval queue</h2></div></div>%s</section>
            """.formatted(USERS.size(), QUIZZES.size(), ATTEMPTS.size(), users, pending));
    }

    private static String page(String title, String role, String content) {
        User user = currentUser.get();
        String flash = successMessage.isBlank() ? "" : "<div class='flash'>✓ " + successMessage + "</div>";
        successMessage = "";
        String participantLinks = "<a href='/participant'>Dashboard</a><a href='/quizzes'>Explore Quizzes</a><a href='/performance'>My Performance</a><a href='/leaderboard'>Leaderboard</a>";
        String links = role.equals("participant") ? participantLinks : role.equals("creator") ? "<a href='/creator'>Creator Dashboard</a><a href='/quizzes'>Quiz Library</a>" : "<a href='/admin'>Admin Dashboard</a><a href='/quizzes'>Quiz Library</a>";
        
        String avatar = user != null ? initials(user.name) : "??";
        String userName = user != null ? user.name : "Guest";
        String roleName = user != null ? user.role : "Guest";

        return """
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="description" content="Quizora Java online quiz platform"><title>%s · Quizora</title><style>%s</style></head>
            <body><header class="topbar"><a class="brand" href="/"><span>&lt;/&gt;</span>quiz<b>ora</b></a><nav>%s</nav><div class="role-switch"><span class="avatar">%s</span><div><b>%s</b><small>%s</small></div><a href="/logout" style="margin-left:10px;font-size:11px;font-weight:700;color:var(--purple)">Logout</a></div></header>%s<main>%s</main><footer><a class="brand" href="/"><span>&lt;/&gt;</span>quiz<b>ora</b></a><p>Java learning made practical. Built entirely with Java.</p><small>© %d Quizora. Educational project.</small></footer></body></html>
            """.formatted(title, CSS, links, avatar, userName, roleName, flash, content, LocalDate.now().getYear());
    }

    private static String initials(String name) {
        String[] parts = name.split(" ");
        if (parts.length == 0 || name.isBlank()) return "?";
        if (parts.length == 1) return name.substring(0, 1).toUpperCase();
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }

    private static String notFound() {
        return page("Not Found", "participant", "<section class='result-card'><h1>Page not found</h1><p>The page you requested does not exist.</p><a class='button' href='/'>Return home</a></section>");
    }

    private static void send(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write(body);
    }

    private static void redirect(HttpServletResponse response, String location) throws IOException {
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", location);
    }

    private static final class User implements Serializable {
        final String name, email, password, role, status;
        User(String name, String email, String password, String role, String status) { this.name = name; this.email = email; this.password = password; this.role = role; this.status = status; }
    }

    private static final class Question implements Serializable {
        final String text;
        final String[] options;
        final int correctOptionIndex;
        Question(String text, String[] options, int correctOptionIndex) { this.text = text; this.options = options; this.correctOptionIndex = correctOptionIndex; }
    }

    private static final class Quiz implements Serializable {
        final String title, description, level;
        final int duration;
        String status;
        final String creatorEmail;
        final List<Question> questions = new ArrayList<>();
        Quiz(String title, String description, int duration, String level, String status, String creatorEmail) { this.title = title; this.description = description; this.duration = duration; this.level = level; this.status = status; this.creatorEmail = creatorEmail; }
    }

    private static final class QuizAttempt implements Serializable {
        final String userEmail;
        final String quizTitle;
        final int score;
        final int maxScore;
        final String date;
        QuizAttempt(String userEmail, String quizTitle, int score, int maxScore, String date) { this.userEmail = userEmail; this.quizTitle = quizTitle; this.score = score; this.maxScore = maxScore; this.date = date; }
    }

    private static final String CSS = """
        @import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=Manrope:wght@600;700;800&display=swap');
        :root{--purple:#7654d5;--ink:#282139;--muted:#81798d;--line:#e9e5ef;--bg:#faf9fd}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:'DM Sans',sans-serif}a{text-decoration:none;color:inherit}button,input,select,textarea{font:inherit}.topbar{height:76px;padding:0 max(24px,calc((100% - 1280px)/2));display:flex;align-items:center;justify-content:space-between;background:#fff;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}.brand{font:800 22px Manrope;display:flex;align-items:center;gap:8px}.brand>span{width:39px;height:39px;border-radius:11px;display:grid;place-items:center;background:linear-gradient(135deg,#8d69e8,#6542c5);color:#fff;font-size:15px;box-shadow:0 7px 15px #7554d548}.brand>b{color:var(--purple);margin-left:-8px}.topbar nav{height:100%;display:flex;align-items:center;gap:32px}.topbar nav a{font-size:13px;font-weight:600;color:#766e82}.topbar nav a:hover{color:var(--purple)}.role-switch{display:flex;align-items:center;gap:9px}.role-switch>div{display:flex;flex-direction:column;font-size:11px}.role-switch small{color:#948d9e}.avatar{width:35px;height:35px;border-radius:9px;background:#eee8ff;color:#704ece;display:grid;place-items:center;font-size:11px;font-weight:800}.role-switch details{position:relative}.role-switch summary{list-style:none;cursor:pointer}.role-switch details div{position:absolute;right:0;top:24px;width:145px;padding:7px;background:white;border:1px solid var(--line);border-radius:9px;box-shadow:0 12px 30px #2f25452a}.role-switch details a{display:block;padding:8px;font-size:11px}.role-switch details a:hover{background:#f4f0fc;color:var(--purple)}main{max-width:1280px;margin:auto;padding:28px 24px 70px}.hero{min-height:260px;padding:42px 55px;border-radius:22px;background:linear-gradient(120deg,#f0e9ff,#faf8ff 62%,#ede5ff);display:flex;justify-content:space-between;overflow:hidden}.kicker{color:var(--purple);font-size:9px;font-weight:800;letter-spacing:1.5px}.hero h1,.page-title h1,.dashboard-title h1{font:800 30px Manrope;margin:15px 0 7px}.hero p,.page-title p,.dashboard-title p{color:#726b7d;font-size:13px}.streak{display:flex;align-items:center;gap:10px;margin-top:25px}.streak>span{width:41px;height:41px;border-radius:11px;background:#fff1df;display:grid;place-items:center}.streak div{display:flex;flex-direction:column;font-size:12px}.streak small{font-size:10px;color:#8e8797}.hero-art{width:340px;position:relative}.code-window{position:absolute;right:12px;width:220px;height:135px;background:#ffffffd9;border:1px solid #e2daef;border-radius:14px;padding:16px;transform:rotate(3deg);box-shadow:0 18px 32px #40325e20}.code-window i{display:inline-block;width:7px;height:7px;border-radius:50%;background:#ff858c;margin-right:4px}.code-window i:nth-child(2){background:#ffd06e}.code-window i:nth-child(3){background:#75dca1}.code-window code{display:block;margin-top:17px;font-size:11px;line-height:1.8;color:#e07462}.code-window code b{color:var(--purple)}.trophy{position:absolute;bottom:0;left:28px;width:100px;height:100px;border-radius:50%;background:#fff;display:grid;place-items:center;font-size:48px;box-shadow:0 14px 28px #51406e25}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:15px;margin:18px 0 31px}.stats.three{grid-template-columns:repeat(3,1fr)}.stats article{display:flex;align-items:center;gap:13px;background:#fff;border:1px solid var(--line);border-radius:14px;padding:17px}.stat-icon{width:45px;height:45px;border-radius:12px;display:grid;place-items:center;font-size:20px}.purple{background:#eee8ff;color:#7653d5}.orange{background:#ffeedb;color:#e98b34}.blue{background:#e3f4ff;color:#3792cf}.pink{background:#ffe5ed;color:#df5d88}.stats article div{display:flex;flex-direction:column}.stats small{font-size:8px;letter-spacing:1px;color:#978f9e;font-weight:800}.stats strong{font:800 22px Manrope}.stats em{font-style:normal;font-size:9px;color:#72a983}.panel{background:#fff;border:1px solid var(--line);border-radius:17px;padding:24px}.section-head{display:flex;justify-content:space-between;align-items:end;margin-bottom:14px}.section-head h2,.panel h2{font:800 19px Manrope;margin:4px 0}.section-head>a,.rank-panel>a{font-size:10px;font-weight:700;color:var(--purple)}.continue{margin-bottom:32px}.continue-row{display:flex;align-items:center;gap:20px}.java-tile{width:90px;height:90px;border-radius:15px;background:linear-gradient(135deg,#7049cc,#9871e8);color:#fff;display:flex;flex-direction:column;justify-content:center;align-items:center;font:bold 30px monospace}.java-tile small{font:800 8px sans-serif;letter-spacing:1px}.grow{flex:1}.split{display:flex;justify-content:space-between;align-items:center}.pill{padding:5px 8px;border-radius:5px;font-size:8px;font-weight:800;letter-spacing:.7px}.amber{background:#fff0dc;color:#dc8735}.green{background:#e4f7ec;color:#439d68}.pink{background:#ffe6ed;color:#d9567e}.grow p,.quiz-card p{font-size:11px;color:#766f7f}.progress,.skill{height:6px;border-radius:5px;background:#eeeaf3}.progress i{display:block;width:40%;height:100%;background:var(--purple);border-radius:5px}.muted{color:#9991a0}.button{display:inline-flex;align-items:center;justify-content:center;border:0;border-radius:9px;background:linear-gradient(135deg,#7957d8,#6240bf);color:#fff!important;padding:11px 17px;font-size:10px;font-weight:700;box-shadow:0 7px 16px #7552d439;cursor:pointer}.main-grid{display:grid;grid-template-columns:1fr 290px;gap:22px}.quiz-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.quiz-grid.library{grid-template-columns:repeat(4,1fr)}.quiz-card{background:#fff;border:1px solid var(--line);border-radius:14px;padding:17px;transition:.2s}.quiz-card:hover{transform:translateY(-3px);box-shadow:0 12px 27px #49356815}.quiz-top{display:flex;justify-content:space-between}.quiz-icon{width:42px;height:42px;border-radius:11px;background:#eee8ff;color:var(--purple);display:grid;place-items:center;font:bold 15px monospace}.quiz-card h3{font:700 14px Manrope;margin:17px 0 7px}.quiz-card p{height:35px}.quiz-meta{display:flex;gap:14px;padding:10px 0;border-top:1px solid #efecf2;color:#968e9e;font-size:9px}.quiz-card>a{display:block;text-align:center;padding:8px;border-radius:7px;background:#f4f0fb;color:var(--purple);font-size:10px;font-weight:700}.rank-panel{background:linear-gradient(150deg,#f6f1ff,#fff);border:1px solid #e4dcf3;border-radius:16px;padding:20px}.rank-panel h3{margin:5px 0}.podium{height:130px;display:flex;align-items:center;justify-content:center;gap:10px}.podium>div{display:flex;flex-direction:column;align-items:center}.podium b{width:39px;height:39px;border-radius:50%;background:#eee8fa;color:#7250cd;display:grid;place-items:center;font-size:10px}.podium small{font-size:8px}.podium .winner{transform:translateY(-17px)}.podium .winner b{width:48px;height:48px;background:var(--purple);color:#fff}.me{display:flex;align-items:center;gap:8px;padding:9px;background:white;border-radius:9px;font-size:9px}.me>span{width:28px;height:28px;border-radius:7px;background:#eee8fa;display:grid;place-items:center}.me>div{display:flex;flex-direction:column;flex:1}.me em{font-style:normal;color:var(--purple);font-weight:700}.rank-panel>a{display:block;text-align:center;margin-top:14px}.history{margin-top:32px}.table{background:#fff;border:1px solid var(--line);border-radius:13px;overflow:hidden}.tr{display:grid;grid-template-columns:2fr 1.1fr .7fr .7fr .8fr;align-items:center;min-height:51px;padding:8px 17px;border-top:1px solid #f0edf3;font-size:10px;color:#756e7d}.tr.th{border:0;background:#faf9fc;font-size:8px;letter-spacing:.7px;color:#97909d;font-weight:800}.tr mark{justify-self:start;padding:4px 7px;background:#e7f7ed;color:#409868;border-radius:5px;font-size:8px}.page-title{text-align:center;margin:27px 0 31px}.search{max-width:650px;margin:0 auto 28px;display:flex;align-items:center;gap:9px;padding:7px 7px 7px 15px;background:#fff;border:1px solid var(--line);border-radius:11px}.search input{border:0;outline:0;flex:1}.search button,.reply button,.approval button{border:0;border-radius:7px;background:#eee8fa;color:var(--purple);padding:8px 13px;font-size:10px;font-weight:700}.empty{grid-column:1/-1;text-align:center;padding:50px;color:var(--muted)}.quiz-room{max-width:830px;margin:25px auto;background:#fff;border:1px solid var(--line);border-radius:19px;padding:32px}.quiz-room header{display:flex;justify-content:space-between;align-items:center}.quiz-room header h2{margin:5px 0;font:800 20px Manrope}.timer{display:grid;grid-template-columns:auto auto;gap:0 6px;color:var(--purple)}.timer small{grid-column:1/-1;font-size:7px;letter-spacing:1px}.question-progress{height:5px;background:#eeeaf4;margin:25px -32px}.question-progress i{display:block;width:20%;height:100%;background:var(--purple)}.quiz-room form>h1{font:800 24px Manrope;margin-top:30px}.answer{display:flex;align-items:center;gap:13px;border:1px solid var(--line);border-radius:11px;padding:14px;margin:10px 0;cursor:pointer}.answer:hover{border-color:#9e83e5;background:#faf8ff}.answer input{accent-color:var(--purple)}.answer span{width:28px;height:28px;border-radius:7px;background:#f2eef9;display:grid;place-items:center;color:var(--purple)}.quiz-actions{display:flex;justify-content:space-between;align-items:center;margin-top:27px}.quiz-actions>a{font-size:11px;color:#8b8492}.result-card{max-width:720px;margin:50px auto;text-align:center;background:#fff;border:1px solid var(--line);border-radius:20px;padding:45px}.result-ring{width:110px;height:110px;border:9px solid #7d5bd9;border-radius:50%;display:grid;place-items:center;margin:0 auto 23px;font:800 24px Manrope}.result-card h1{font:800 28px Manrope}.result-card p{color:var(--muted);line-height:1.7}.result-stats{display:grid;grid-template-columns:repeat(3,1fr);margin:25px 0;border:1px solid var(--line);border-radius:11px}.result-stats div{padding:17px;display:flex;flex-direction:column;border-right:1px solid var(--line)}.result-stats div:last-child{border:0}.result-stats small{color:var(--muted);font-size:9px}.performance-hero{display:flex;justify-content:space-between;align-items:end;padding:30px 40px;background:linear-gradient(130deg,#6f4dce,#9673e8);color:white;border-radius:18px}.performance-hero>div:first-child{display:flex;flex-direction:column}.performance-hero small{font-size:9px;letter-spacing:1px}.performance-hero strong{font:800 40px Manrope}.performance-hero span{font-size:11px}.chart{height:100px;width:350px;display:flex;align-items:end;gap:14px;border-bottom:1px solid #ffffff60}.chart i{flex:1;background:#ffffffb8;border-radius:5px 5px 0 0}.report-grid,.creator-grid,.admin-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin:20px 0}.skill{height:auto;background:none;margin:21px 0}.skill span{display:flex;justify-content:space-between;font-size:11px}.skill>i{display:block;height:7px;margin-top:7px;background:linear-gradient(90deg,#7654d5,#a587ed);border-radius:6px}.feedback blockquote{margin:24px 0 8px;padding:18px;background:#f7f4fc;border-left:3px solid var(--purple);font-size:12px;line-height:1.7}.feedback small{color:var(--muted)}.leaderboard{max-width:750px;margin:auto;background:#fff;border:1px solid var(--line);border-radius:16px;overflow:hidden}.leader-row{display:grid;grid-template-columns:45px 50px 1fr 90px;align-items:center;padding:13px 20px;border-bottom:1px solid #efecf2;font-size:12px}.leader-row>em{text-align:right;font-style:normal;color:var(--purple);font-weight:700}.leader-row.current{background:#f2edff}.leader-row small{color:var(--muted)}.dashboard-title{display:flex;justify-content:space-between;align-items:center;margin:18px 0 25px}.health{padding:9px 13px;border-radius:20px;background:#e6f7ec;color:#3c9662;font-size:10px}.creator-row{grid-template-columns:2fr .8fr .7fr .8fr .8fr}.creator-table{border:0}.status{justify-self:start;padding:5px 8px;border-radius:6px;font-size:8px;font-weight:800}.status.published{background:#e5f7ec;color:#3f9965}.status.pending{background:#fff0dc;color:#dc8735}.status.rejected{background:#ffe7eb;color:#d75a72}.creator-row a{color:var(--purple);font-weight:700}.form{display:flex;flex-direction:column;gap:12px;margin-top:18px}.form label{display:flex;flex-direction:column;gap:5px;color:#665f6f;font-size:10px;font-weight:700}.form input,.form select,.form textarea,.reply input{width:100%;padding:10px;border:1px solid #ded9e5;border-radius:8px;outline:0;background:#fcfbfd}.form textarea{min-height:75px;resize:vertical}.form input:focus,.form textarea:focus{border-color:#987ee0}.form-row{display:grid;grid-template-columns:repeat(3,1fr);gap:9px}.form .button{align-self:start}.message{display:flex;gap:11px;margin-top:20px;padding:15px;background:#f8f6fb;border-radius:11px}.message>div{flex:1}.message b,.message small{display:block;font-size:10px}.message small{color:var(--muted)}.message p{font-size:11px}.reply{display:flex;gap:7px;margin-top:12px}.admin-grid{grid-template-columns:2fr 1fr}.admin-user{grid-template-columns:2fr 1fr .7fr .5fr}.admin-user strong{display:flex;flex-direction:column}.admin-user strong small{font-weight:400;color:var(--muted);font-size:8px}.admin-user form{justify-self:start}.danger{border:0;color:#d5576e;background:#ffe9ed;padding:5px 8px;border-radius:5px;font-size:8px;cursor:pointer}details summary{cursor:pointer;color:var(--purple);font-size:11px;font-weight:700;margin-top:15px}.inline-form{display:grid;grid-template-columns:1fr 1fr 1fr auto;align-items:end}.check{flex-direction:row!important;align-items:center}.check input{width:auto}.approvals{margin-top:20px}.approval{display:grid;grid-template-columns:1fr 100px 170px;align-items:center;padding:13px 0;border-top:1px solid var(--line)}.approval>div{display:flex;flex-direction:column}.approval small{color:var(--muted);font-size:9px}.approval form{display:flex;gap:7px}footer{padding:35px max(24px,calc((100% - 1232px)/2));border-top:1px solid var(--line);background:#fff;display:flex;align-items:center;gap:28px}footer p{flex:1;color:var(--muted);font-size:11px}footer>small{color:#9a929f;font-size:9px}.flash{position:fixed;right:25px;top:92px;padding:13px 17px;border-radius:10px;background:#292338;color:white;font-size:11px;z-index:10;box-shadow:0 12px 28px #3329443d}
        @media(max-width:900px){.topbar nav{gap:13px}.stats{grid-template-columns:1fr 1fr}.quiz-grid.library{grid-template-columns:1fr 1fr}.main-grid{grid-template-columns:1fr}.rank-panel{display:none}.admin-grid{grid-template-columns:1fr}.hero-art{display:none}.chart{width:250px}}
        @media(max-width:650px){.topbar{height:auto;padding:13px 16px;flex-wrap:wrap;gap:12px}.topbar nav{order:3;width:100%;overflow:auto;padding:8px 0}.role-switch>div{display:none}main{padding:18px 14px 50px}.hero{padding:28px 23px}.stats,.stats.three,.quiz-grid,.quiz-grid.library,.report-grid,.creator-grid{grid-template-columns:1fr}.continue-row{flex-wrap:wrap}.grow{min-width:calc(100% - 115px)}.continue-row>.button{width:100%}.table{overflow:auto}.tr{min-width:620px}.performance-hero{display:block}.chart{width:100%;margin-top:20px}.dashboard-title{align-items:flex-start;gap:15px}.dashboard-title h1{font-size:24px}.form-row,.inline-form{grid-template-columns:1fr}.approval{grid-template-columns:1fr;gap:9px}.quiz-room{padding:20px}.question-progress{margin:20px -20px}.result-card{padding:28px 18px}.result-stats{grid-template-columns:1fr}.result-stats div{border-right:0;border-bottom:1px solid var(--line)}footer{display:block}footer p{margin:20px 0}}
        """;
}
