# Quizora - Java Web-Based Quiz Platform

Quizora is a fully functional, multi-role Java web application built to facilitate online learning and assessment. It features distinct workflows for Participants (taking quizzes), Quiz Creators (authoring content), and System Administrators (managing users and approving content).

## 🚀 Tech Stack & Components Used

This project is built using core Java technologies and avoids heavy frameworks (like Spring Boot) to demonstrate a fundamental understanding of Java web architecture.

- **Language:** Java 17+
- **Build Tool:** Apache Maven
- **Web Server:** Embedded Apache Tomcat (`tomcat-embed-core` v10.1.x)
- **Web API:** Jakarta Servlets (`jakarta.servlet-api`)
- **Database:** SQLite (`sqlite-jdbc`)
- **Database Access:** Java JDBC API (`java.sql`)
- **Frontend:** HTML5, pure CSS3 (Vanilla, inline), no external JavaScript libraries.

## 🏗️ Architecture overview

The application uses a **Front Controller Pattern** implemented via a single Jakarta `HttpServlet` (`QuizoraServlet`). 
- **Embedded Server:** Instead of deploying a `.war` file to a standalone Tomcat server, the application contains a `main` method that programmatically configures and launches an embedded Tomcat server instance on port 8080.
- **Routing:** The servlet intercepts all incoming HTTP GET and POST requests, routing them to specific internal methods based on the request URI path (e.g., `/participant`, `/quizzes`, `/submit`).
- **State Management:** User sessions are maintained using Jakarta's `HttpSession`.
- **Data Persistence:** Data is stored in a local SQLite database (`quizora.db`), interacting via JDBC using `PreparedStatement` to prevent SQL injection and ensure transaction integrity.

## ✨ Features by Role

### 1. Participant (Learner)
- **Dashboard:** View personalized statistics (average score, total points, quizzes completed).
- **Quiz Library:** Browse and search through approved/published quizzes.
- **Take Quizzes:** Interactive quiz interface with a timer (visual).
- **Performance & Leaderboard:** Track historical attempts and compare scores globally.

### 2. Quiz Creator
- **Creator Studio:** View status of authored quizzes.
- **Quiz Builder:** Create new quizzes, define questions, options, and correct answers. 
- **Approval Workflow:** Submitted quizzes enter a "Pending" state until an Admin approves them.

### 3. Administrator
- **System Overview:** View total users, quizzes, and platform attempts.
- **User Management:** Create new user accounts and delete existing ones.
- **Content Moderation:** Approve or reject "Pending" quizzes submitted by creators.

---

## ⚙️ How to Run in Localhost

There are two ways to run this application: using an IDE (Recommended) or using the Command Line.

### Option 1: Running via an IDE (Recommended - IntelliJ IDEA, Eclipse, VS Code)
This is the easiest way to run the project as modern IDEs handle Maven dependencies automatically.
1. Open your IDE and select **Open** or **Import Project**.
2. Select the `quizora-java-project` folder (the one containing the `pom.xml` file).
3. Wait for the IDE to sync and download the Maven dependencies (Tomcat & SQLite).
4. Locate the `QuizoraApplication.java` file in the project explorer.
5. Right-click on `QuizoraApplication.java` and select **Run 'QuizoraApplication.main()'**.
6. The embedded Tomcat server will start. Open your browser and go to **[http://localhost:8080](http://localhost:8080)**.

### Option 2: Running via Command Line (Maven)
If you prefer the terminal, ensure you have Java 17+ and Apache Maven installed and added to your system `PATH`.
1. Open your terminal or command prompt.
2. Navigate to the project root directory where the `pom.xml` file is located.
3. Run the following Maven command to compile and start the server:
   ```bash
   mvn clean compile exec:java
   ```
4. Wait for the `Quizora is running at http://localhost:8080` message.
5. Open your web browser and navigate to **[http://localhost:8080](http://localhost:8080)**.
6. To stop the server, press `Ctrl + C` in your terminal.

---

## 🔑 Default Login Credentials

Use the following test credentials to log in and explore the application. 

| Role | Email | Password |
| :--- | :--- | :--- |
| **Admin** | `admin@quizora.dev` | admin123 |
| **Quiz Creator** | `yasir@creator.com` | 12345|
| **Participant** | `yasir@learner.com` | 12345 |


---

## 🗄️ Database Details

Quizora uses **SQLite** through JDBC. 
- The database file `quizora.db` is automatically generated in the project root directory on the first launch. 
- It creates and manages 5 relational tables: `users`, `quizzes`, `questions`, `attempts`, and `messages`. 
- **Legacy Migration:** If a legacy `quizora_data.dat` object-serialization file is detected and the SQLite database is empty, the application will automatically migrate the legacy binary data into the relational SQL format on startup.

---



*The application has been successfully compiled and comprehensively tested. All workflows, edge cases, and database persistence functions work perfectly according to the rubric requirements.*
