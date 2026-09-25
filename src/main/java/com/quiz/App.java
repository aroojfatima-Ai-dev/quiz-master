package com.quiz;

import com.quiz.dao.ClassDAO;
import com.quiz.dao.ResultDAO;
import com.quiz.dao.TestDAO;
import com.quiz.dao.UserDAO;
import com.quiz.model.User;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Main JavaFX Application class for QuizMaster.
 * Features Water Bubble Animated Background on every screen, Multi-Screen Hub Architecture,
 * Plain-Text Authentication, Class/Code Isolation System, RTL Urdu Support, Timer, and Answer Review.
 */
public class App extends Application {

    private final UserDAO userDAO = new UserDAO();
    private final TestDAO testDAO = new TestDAO();
    private final ResultDAO resultDAO = new ResultDAO();
    private final ClassDAO classDAO = new ClassDAO();

    // Regex pattern for validating email addresses
    private static final Pattern EMAIL_PATTERN = 
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    // Regex pattern for username (min 3 chars, alphanumeric, underscore, dot)
    private static final Pattern USERNAME_PATTERN = 
            Pattern.compile("^[a-zA-Z0-9_.]{3,}$");

    // Clean modern input field styling
    private static final String INPUT_STYLE = 
            "-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; " +
            "-fx-border-radius: 6; -fx-background-radius: 6; " +
            "-fx-padding: 10 12; -fx-font-size: 13px; -fx-prompt-text-fill: #94a3b8;";

    private static final String COMBO_STYLE = 
            "-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; " +
            "-fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 13px;";

    /**
     * In-memory model class for holding draft test details before committing to DB.
     */
    public static class DraftTestDetails {
        public String title = "";
        public String language = "English";
        public int timeMinutes = 15;
        public int targetQuestionCount = 5;
        public String expiryAction = "AUTO_SUBMIT";
        public boolean isPublic = true;
        public Integer classId = null;
        public String questionOrder = "SEQUENTIAL";
    }

    /**
     * In-memory model class for holding draft question data before committing to DB.
     */
    public static class DraftQuestion {
        public String questionText = "";
        public String optionA = "";
        public String optionB = "";
        public String optionC = "";
        public String optionD = "";
        public String correctOption = "A";
        public String topic = "General";
        public String difficulty = "MEDIUM";
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("QuizMaster Application");
        showWelcomeScreen(stage);
        stage.show();
    }

    /**
     * Seamless screen transition helper: swaps the root container of the active Scene.
     * Prevents the stage/window from shrinking or un-maximizing during navigation.
     */
    private void setScreenRoot(Stage stage, Parent rootContainer, double defaultWidth, double defaultHeight) {
        if (stage.getScene() == null) {
            Scene scene = new Scene(rootContainer, defaultWidth, defaultHeight);
            stage.setScene(scene);
        } else {
            stage.getScene().setRoot(rootContainer);
        }
    }

    /**
     * Helper method to create a full-window container with animated water bubbles floating outside the center card.
     */
    private StackPane createCenteredRoot(Node content) {
        StackPane root = new StackPane();
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #f0f9ff;"); // Light sky blue canvas

        // Animated Water Bubble Layer
        Pane bubbleLayer = new Pane();
        bubbleLayer.setMouseTransparent(true);

        Random random = new Random();
        int bubbleCount = 42;

        for (int i = 0; i < bubbleCount; i++) {
            // Position bubbles outside center card area (left margin 0.02..0.28, right margin 0.72..0.98)
            double relX = (i % 2 == 0) ? (0.02 + random.nextDouble() * 0.26) : (0.72 + random.nextDouble() * 0.26);
            double relY = 0.02 + random.nextDouble() * 0.96;
            double radius = 5 + random.nextDouble() * 15;

            Circle bubble = new Circle(radius);
            bubble.setFill(Color.web("#38bdf8"));
            bubble.setStroke(Color.web("#bae6fd"));
            bubble.setStrokeWidth(1.2);
            bubble.setOpacity(0.15 + random.nextDouble() * 0.3);

            bubble.layoutXProperty().bind(root.widthProperty().multiply(relX));
            bubble.layoutYProperty().bind(root.heightProperty().multiply(relY));

            // Soft pulse / twinkle opacity
            FadeTransition fade = new FadeTransition(Duration.seconds(1.4 + random.nextDouble() * 2.2), bubble);
            fade.setFromValue(0.12 + random.nextDouble() * 0.15);
            fade.setToValue(0.45 + random.nextDouble() * 0.25);
            fade.setCycleCount(FadeTransition.INDEFINITE);
            fade.setAutoReverse(true);
            fade.play();

            // Continuous 3D-like upward drift with gentle sine wave wobble
            double speedPixels = 0.4 + random.nextDouble() * 0.8;
            double wobbleSpeed = 0.02 + random.nextDouble() * 0.04;
            double[] waveAngle = new double[]{ random.nextDouble() * Math.PI * 2 };
            double initialYRatio = relY;

            Timeline drift = new Timeline(new KeyFrame(Duration.millis(30), e -> {
                double h = root.getHeight() > 0 ? root.getHeight() : 650;
                double currentY = bubble.getTranslateY();
                double nextY = currentY - speedPixels;

                if (nextY < -(h * initialYRatio + 40)) {
                    nextY = h * (1.0 - initialYRatio) + 40;
                }
                bubble.setTranslateY(nextY);

                waveAngle[0] += wobbleSpeed;
                bubble.setTranslateX(Math.sin(waveAngle[0]) * 14);
            }));
            drift.setCycleCount(Timeline.INDEFINITE);
            drift.play();

            bubbleLayer.getChildren().add(bubble);
        }

        root.getChildren().addAll(bubbleLayer, content);
        return root;
    }

    /**
     * Builds and displays the Welcome / Splash Screen with floating water bubbles.
     */
    private void showWelcomeScreen(Stage stage) {
        VBox centerBox = new VBox(26);
        centerBox.setMaxWidth(550);
        centerBox.setAlignment(Pos.CENTER);
        centerBox.setPadding(new Insets(36));
        centerBox.setStyle("-fx-background-color: rgba(15, 23, 42, 0.85); -fx-background-radius: 16; -fx-border-color: #38bdf8; -fx-border-radius: 16; -fx-border-width: 1.5; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.25), 20, 0, 0, 8);");

        Label titleLabel = new Label("Welcome to QuizMaster");
        titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 38));
        titleLabel.setTextFill(Color.web("#f0f9ff"));

        Label subtitleLabel = new Label("Learn, Test, Improve");
        subtitleLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 18));
        subtitleLabel.setTextFill(Color.web("#7dd3fc"));

        VBox titleContainer = new VBox(10, titleLabel, subtitleLabel);
        titleContainer.setAlignment(Pos.CENTER);

        Button getStartedButton = new Button("Get Started");
        getStartedButton.setPrefWidth(220);
        getStartedButton.setPrefHeight(46);
        getStartedButton.setStyle("-fx-background-color: #38bdf8; -fx-text-fill: #0f172a; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 8; -fx-cursor: hand;");

        getStartedButton.setOnAction(e -> showLoginScreen(stage));

        centerBox.getChildren().addAll(titleContainer, getStartedButton);

        StackPane root = createCenteredRoot(centerBox);
        setScreenRoot(stage, root, 800, 650);
    }

    /**
     * Builds and displays the Login Screen.
     */
    private void showLoginScreen(Stage stage) {
        VBox form = new VBox(18);
        form.setMaxWidth(460);
        form.setMaxHeight(Region.USE_PREF_SIZE); // Fit card height snugly to content!
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(34, 38, 34, 38));
        form.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 18; -fx-background-radius: 18; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 20, 0, 0, 6);");

        Label titleLabel = new Label("QuizMaster Login");
        titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
        titleLabel.setTextFill(Color.web("#1e293b"));

        Label subtitleLabel = new Label("Sign in to your account");
        subtitleLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 14));
        subtitleLabel.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(6, titleLabel, subtitleLabel);
        headerBox.setAlignment(Pos.CENTER);

        TextField usernameOrEmailField = new TextField();
        usernameOrEmailField.setPromptText("Username or Email");
        usernameOrEmailField.setStyle(INPUT_STYLE);
        usernameOrEmailField.setPrefHeight(48);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setStyle(INPUT_STYLE);
        passwordField.setPrefHeight(48);

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);

        Button loginButton = new Button("Login");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setPrefHeight(48);
        loginButton.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; -fx-background-radius: 8; -fx-cursor: hand;");

        Hyperlink goToRegisterLink = new Hyperlink("Don't have an account? Register here");
        goToRegisterLink.setStyle("-fx-text-fill: #0284c7; -fx-font-size: 14px; -fx-border-color: transparent;");

        goToRegisterLink.setOnAction(e -> showRegisterScreen(stage));

        loginButton.setOnAction(e -> {
            String input = usernameOrEmailField.getText().trim();
            String pass = passwordField.getText();

            if (input.isEmpty() || pass.isEmpty()) {
                showMessage(messageLabel, "Please enter both username/email and password.", false);
                return;
            }

            try {
                User user = userDAO.findUserForLogin(input, pass);

                if (user != null) {
                    if (user.isTeacher()) {
                        showTeacherDashboard(stage, user, null);
                    } else {
                        showStudentDashboard(stage, user);
                    }
                } else {
                    showMessage(messageLabel, "Invalid username/email or password.", false);
                }

            } catch (SQLException ex) {
                showMessage(messageLabel, "Database connection error: " + ex.getMessage(), false);
            }
        });

        form.getChildren().addAll(
                headerBox,
                usernameOrEmailField,
                passwordField,
                loginButton,
                messageLabel,
                goToRegisterLink
        );

        StackPane root = createCenteredRoot(form);
        setScreenRoot(stage, root, 750, 650);
    }

    /**
     * Builds and displays the Registration Screen.
     */
    private void showRegisterScreen(Stage stage) {
        VBox form = new VBox(16);
        form.setMaxWidth(460);
        form.setMaxHeight(Region.USE_PREF_SIZE); // Fit card height snugly to content!
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(32, 36, 32, 36));
        form.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 18; -fx-background-radius: 18; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 20, 0, 0, 6);");

        Label titleLabel = new Label("Create Account");
        titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
        titleLabel.setTextFill(Color.web("#1e293b"));

        Label subtitleLabel = new Label("Join QuizMaster today");
        subtitleLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 14));
        subtitleLabel.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(6, titleLabel, subtitleLabel);
        headerBox.setAlignment(Pos.CENTER);

        TextField usernameField = new TextField();
        usernameField.setPromptText("Username (min 3 chars)");
        usernameField.setStyle(INPUT_STYLE);
        usernameField.setPrefHeight(46);

        TextField emailField = new TextField();
        emailField.setPromptText("Email Address");
        emailField.setStyle(INPUT_STYLE);
        emailField.setPrefHeight(46);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setStyle(INPUT_STYLE);
        passwordField.setPrefHeight(46);

        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Confirm Password");
        confirmPasswordField.setStyle(INPUT_STYLE);
        confirmPasswordField.setPrefHeight(46);

        Label roleLabel = new Label("Select Role:");
        roleLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        roleLabel.setTextFill(Color.web("#475569"));

        RadioButton studentRadio = new RadioButton("Student");
        RadioButton teacherRadio = new RadioButton("Teacher");
        ToggleGroup roleGroup = new ToggleGroup();
        studentRadio.setToggleGroup(roleGroup);
        teacherRadio.setToggleGroup(roleGroup);
        studentRadio.setSelected(true);

        HBox roleBox = new HBox(18, roleLabel, studentRadio, teacherRadio);
        roleBox.setAlignment(Pos.CENTER_LEFT);
        roleBox.setStyle("-fx-background-color: #f8fafc; -fx-padding: 12 16; -fx-background-radius: 8; -fx-border-color: #cbd5e1; -fx-border-radius: 8;");

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);

        Button registerButton = new Button("Register");
        registerButton.setMaxWidth(Double.MAX_VALUE);
        registerButton.setPrefHeight(48);
        registerButton.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; -fx-background-radius: 8; -fx-cursor: hand;");

        Hyperlink goToLoginLink = new Hyperlink("Already have an account? Login here");
        goToLoginLink.setStyle("-fx-text-fill: #0284c7; -fx-font-size: 14px; -fx-border-color: transparent;");

        goToLoginLink.setOnAction(e -> showLoginScreen(stage));

        registerButton.setOnAction(e -> {
            String username = usernameField.getText().trim();
            String email = emailField.getText().trim();
            String password = passwordField.getText();
            String confirmPassword = confirmPasswordField.getText();
            String selectedRole = studentRadio.isSelected() ? "student" : "teacher";

            String validationError = validateInput(username, email, password, confirmPassword);
            if (validationError != null) {
                showMessage(messageLabel, validationError, false);
                return;
            }

            try {
                boolean success = userDAO.registerUser(username, email, password, selectedRole);

                if (success) {
                    showMessage(messageLabel, "Registration successful! You can now login.", true);
                    usernameField.clear();
                    emailField.clear();
                    passwordField.clear();
                    confirmPasswordField.clear();
                }

            } catch (UserDAO.DuplicateUserException ex) {
                showMessage(messageLabel, ex.getMessage(), false);

            } catch (SQLException ex) {
                showMessage(messageLabel, "Database Error: " + ex.getMessage(), false);
            }
        });

        form.getChildren().addAll(
                headerBox,
                usernameField,
                emailField,
                passwordField,
                confirmPasswordField,
                roleBox,
                registerButton,
                messageLabel,
                goToLoginLink
        );

        StackPane root = createCenteredRoot(form);
        setScreenRoot(stage, root, 750, 680);
    }

    private String validateInput(String username, String email, String password, String confirmPassword) {
        if (username.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            return "All fields are required.";
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return "Username must be at least 3 characters (letters, numbers, underscore, dot).";
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return "Please enter a valid email address.";
        }
        if (!password.equals(confirmPassword)) {
            return "Passwords do not match.";
        }
        return null;
    }

    // =========================================================================
    // TEACHER SIDE: HUB & SEPARATE SCREENS
    // =========================================================================

    /**
     * TEACHER DASHBOARD HUB SCREEN
     */
    private void showTeacherDashboard(Stage stage, User user, String optionalSuccessMsg) {
        VBox card = new VBox(22);
        card.setMaxWidth(540);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPadding(new Insets(34, 38, 34, 38));
        card.setAlignment(Pos.CENTER);
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 18; -fx-background-radius: 18; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 20, 0, 0, 6);");

        Label header = new Label("Teacher Hub");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
        header.setTextFill(Color.web("#6b21a8"));

        Label welcomeMsg = new Label("Welcome, " + user.getUsername() + "!");
        welcomeMsg.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        welcomeMsg.setTextFill(Color.web("#1e293b"));

        Label infoLabel = new Label("Email: " + user.getEmail() + " | ID: " + user.getId());
        infoLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 14));
        infoLabel.setTextFill(Color.web("#64748b"));

        VBox profileBox = new VBox(6, header, welcomeMsg, infoLabel);
        profileBox.setAlignment(Pos.CENTER);

        Label statusMsgLabel = new Label();
        statusMsgLabel.setWrapText(true);
        if (optionalSuccessMsg != null && !optionalSuccessMsg.isEmpty()) {
            showMessage(statusMsgLabel, optionalSuccessMsg, true);
        }

        // Navigation Menu Buttons (Lighter Background Theme, Dark Text, Larger Prominent Box Size)
        Button createTestBtn = new Button("➕  Create New Test");
        createTestBtn.setMaxWidth(Double.MAX_VALUE);
        createTestBtn.setPrefHeight(56);
        createTestBtn.setStyle("-fx-background-color: #f3e8ff; -fx-border-color: #d8b4fe; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #581c87; -fx-font-weight: bold; -fx-font-size: 17px; -fx-cursor: hand;");
        createTestBtn.setOnAction(e -> {
            DraftTestDetails freshDraft = new DraftTestDetails();
            List<DraftQuestion> freshQuestions = new ArrayList<>();
            showWizardStep1TestDetails(stage, user, freshDraft, freshQuestions);
        });

        Button myClassesBtn = new Button("🏫  My Classes");
        myClassesBtn.setMaxWidth(Double.MAX_VALUE);
        myClassesBtn.setPrefHeight(56);
        myClassesBtn.setStyle("-fx-background-color: #e0f2fe; -fx-border-color: #7dd3fc; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #0369a1; -fx-font-weight: bold; -fx-font-size: 17px; -fx-cursor: hand;");
        myClassesBtn.setOnAction(e -> showTeacherClassesScreen(stage, user));

        Button myTestsBtn = new Button("📝  My Created Tests");
        myTestsBtn.setMaxWidth(Double.MAX_VALUE);
        myTestsBtn.setPrefHeight(56);
        myTestsBtn.setStyle("-fx-background-color: #ccfbf1; -fx-border-color: #5eead4; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #0f766e; -fx-font-weight: bold; -fx-font-size: 17px; -fx-cursor: hand;");
        myTestsBtn.setOnAction(e -> showTeacherTestsScreen(stage, user));

        Button viewSubmissionsBtn = new Button("📊  View Student Submissions");
        viewSubmissionsBtn.setMaxWidth(Double.MAX_VALUE);
        viewSubmissionsBtn.setPrefHeight(56);
        viewSubmissionsBtn.setStyle("-fx-background-color: #fef3c7; -fx-border-color: #fcd34d; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #b45309; -fx-font-weight: bold; -fx-font-size: 17px; -fx-cursor: hand;");
        viewSubmissionsBtn.setOnAction(e -> showTeacherSubmissionsScreen(stage, user, null));

        Button logoutButton = new Button("🚪  Logout");
        logoutButton.setMaxWidth(Double.MAX_VALUE);
        logoutButton.setPrefHeight(50);
        logoutButton.setStyle("-fx-background-color: #ffe4e6; -fx-border-color: #fecdd3; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #be123c; -fx-font-weight: bold; -fx-font-size: 15px; -fx-cursor: hand;");
        logoutButton.setOnAction(e -> showLoginScreen(stage));

        VBox menuBox = new VBox(14, createTestBtn, myClassesBtn, myTestsBtn, viewSubmissionsBtn, logoutButton);

        card.getChildren().addAll(profileBox, statusMsgLabel, menuBox);

        StackPane rootContainer = createCenteredRoot(card);
        setScreenRoot(stage, rootContainer, 750, 680);
    }

    /**
     * SEPARATE SCREEN: TEACHER MY CLASSES SCREEN (FULL DEDICATED ROSTER & MANAGEMENT)
     */
    private void showTeacherClassesScreen(Stage stage, User user) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(680);
        rootBox.setAlignment(Pos.CENTER);

        Label header = new Label("Class Management & Student Rosters");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));
        header.setTextFill(Color.web("#6b21a8"));

        Label subtitle = new Label("Create classes, update codes, and view enrolled students for each class.");
        subtitle.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
        subtitle.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(4, header, subtitle);
        headerBox.setAlignment(Pos.CENTER);

        Label statusMsgLabel = new Label();
        statusMsgLabel.setWrapText(true);

        // Card 1: Create Class Form
        VBox createCard = new VBox(14);
        createCard.setPadding(new Insets(20));
        createCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12;");

        Label title1 = new Label("➕ Create a New Class");
        title1.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        title1.setTextFill(Color.web("#8e44ad"));

        TextField classNameField = new TextField();
        classNameField.setPromptText("Class Name (e.g. Physics 101)");
        classNameField.setStyle(INPUT_STYLE);
        classNameField.setPrefHeight(42);

        TextField classCodeField = new TextField();
        classCodeField.setPromptText("Chosen Class Code (6 alphanumeric chars, e.g. PHY101)");
        classCodeField.setStyle(INPUT_STYLE);
        classCodeField.setPrefHeight(42);

        Button createBtn = new Button("Create Class");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setPrefHeight(42);
        createBtn.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        createCard.getChildren().addAll(
                title1, 
                new Label("Class Name:"), classNameField, 
                new Label("Class Code (6 alphanumeric characters):"), classCodeField, 
                createBtn
        );

        // Card 2: List of Existing Classes with Roster, Edit Code, and Delete Class
        VBox listCard = new VBox(14);
        listCard.setPadding(new Insets(20));
        listCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12;");

        Label title2 = new Label("🏫 My Existing Classes & Enrolled Students");
        title2.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        title2.setTextFill(Color.web("#1e293b"));

        VBox classesListView = new VBox(14);

        Runnable refreshList = () -> {
            classesListView.getChildren().clear();
            try {
                List<Map<String, Object>> classes = classDAO.getClassesByTeacher(user.getId());
                if (classes.isEmpty()) {
                    Label empty = new Label("You haven't created any classes yet. Use the form above to create your first class!");
                    empty.setTextFill(Color.web("#94a3b8"));
                    empty.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
                    classesListView.getChildren().add(empty);
                } else {
                    for (Map<String, Object> c : classes) {
                        int classId = toInt(c.get("id"));
                        String className = (String) c.get("className");
                        String classCode = (String) c.get("classCode");
                        int studentCount = toInt(c.get("studentCount"));

                        VBox classCard = new VBox(12);
                        classCard.setPadding(new Insets(16));
                        classCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 10; -fx-background-radius: 10; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.03), 8, 0, 0, 2);");

                        // Top Header Row
                        Label cName = new Label(className);
                        cName.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
                        cName.setTextFill(Color.web("#1e293b"));

                        Label cCodeBadge = new Label("Code: " + classCode);
                        cCodeBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                        cCodeBadge.setTextFill(Color.web("#0369a1"));
                        cCodeBadge.setStyle("-fx-background-color: #e0f2fe; -fx-padding: 3 8; -fx-background-radius: 6;");

                        Label cCountBadge = new Label("👥 " + studentCount + (studentCount == 1 ? " Student" : " Students"));
                        cCountBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                        cCountBadge.setTextFill(Color.web("#0f766e"));
                        cCountBadge.setStyle("-fx-background-color: #ccfbf1; -fx-padding: 3 8; -fx-background-radius: 6;");

                        Region spacer = new Region();
                        HBox.setHgrow(spacer, Priority.ALWAYS);

                        // Edit Code Button
                        Button editBtn = new Button("✏️ Edit Code");
                        editBtn.setStyle("-fx-background-color: #f3e8ff; -fx-border-color: #d8b4fe; -fx-border-radius: 6; -fx-background-radius: 6; -fx-text-fill: #6b21a8; -fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand; -fx-padding: 5 10;");

                        editBtn.setOnAction(ev -> {
                            TextInputDialog dialog = new TextInputDialog(classCode);
                            dialog.setTitle("Edit Class Code");
                            dialog.setHeaderText("Update Class Code for '" + className + "'");
                            dialog.setContentText("Enter new 6-character code (letters and digits only):");
                            dialog.showAndWait().ifPresent(newCode -> {
                                String trimmed = newCode.trim().toUpperCase();
                                if (trimmed.length() != 6 || !trimmed.matches("^[a-zA-Z0-9]{6}$")) {
                                    showMessage(statusMsgLabel, "Class code must be exactly 6 alphanumeric characters.", false);
                                    return;
                                }
                                try {
                                    classDAO.updateClassCode(classId, user.getId(), trimmed);
                                    showMessage(statusMsgLabel, "Class code for '" + className + "' successfully updated to " + trimmed + "!", true);
                                    showTeacherClassesScreen(stage, user);
                                } catch (ClassDAO.DuplicateClassCodeException ex) {
                                    showMessage(statusMsgLabel, ex.getMessage(), false);
                                } catch (Exception ex) {
                                    showMessage(statusMsgLabel, "Error updating class code: " + ex.getMessage(), false);
                                }
                            });
                        });

                        // Delete Class Button
                        Button deleteBtn = new Button("🗑️ Delete Class");
                        deleteBtn.setStyle("-fx-background-color: #ffe4e6; -fx-border-color: #fecdd3; -fx-border-radius: 6; -fx-background-radius: 6; -fx-text-fill: #be123c; -fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand; -fx-padding: 5 10;");

                        deleteBtn.setOnAction(ev -> {
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("Confirm Class Deletion");
                            alert.setHeaderText("Delete class '" + className + "' (" + classCode + ")?");
                            alert.setContentText("Are you sure you want to delete this class?\n\n"
                                    + "• All student enrollment records for this class will be deleted.\n"
                                    + "• Any tests assigned to this class will NOT be deleted, but will become unassigned.\n\n"
                                    + "This action cannot be undone.");

                            alert.showAndWait().ifPresent(btnType -> {
                                if (btnType == ButtonType.OK) {
                                    try {
                                        boolean deleted = classDAO.deleteClass(classId, user.getId());
                                        if (deleted) {
                                            showMessage(statusMsgLabel, "Class '" + className + "' deleted successfully.", true);
                                            showTeacherClassesScreen(stage, user);
                                        } else {
                                            showMessage(statusMsgLabel, "Could not delete class (unauthorized or not found).", false);
                                        }
                                    } catch (SQLException ex) {
                                        showMessage(statusMsgLabel, "Database error deleting class: " + ex.getMessage(), false);
                                    }
                                }
                            });
                        });

                        HBox topRow = new HBox(10, cName, cCodeBadge, cCountBadge, spacer, editBtn, deleteBtn);
                        topRow.setAlignment(Pos.CENTER_LEFT);

                        // Enrolled Students Roster
                        VBox rosterBox = new VBox(6);
                        rosterBox.setStyle("-fx-background-color: #f8fafc; -fx-padding: 10 12; -fx-background-radius: 8; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");

                        Label rosterLabel = new Label("Enrolled Students Roster:");
                        rosterLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                        rosterLabel.setTextFill(Color.web("#475569"));
                        rosterBox.getChildren().add(rosterLabel);

                        List<Map<String, Object>> students = classDAO.getEnrolledStudents(classId);
                        if (students.isEmpty()) {
                            Label noStudents = new Label("No students enrolled yet. Students can join using code: " + classCode);
                            noStudents.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
                            noStudents.setTextFill(Color.web("#94a3b8"));
                            rosterBox.getChildren().add(noStudents);
                        } else {
                            for (Map<String, Object> s : students) {
                                HBox sRow = new HBox(8);
                                sRow.setAlignment(Pos.CENTER_LEFT);
                                sRow.setPadding(new Insets(4, 6, 4, 6));

                                Label uLabel = new Label("👤 " + s.get("username"));
                                uLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
                                uLabel.setTextFill(Color.web("#1e293b"));

                                Label eLabel = new Label("✉️ " + s.get("email"));
                                eLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
                                eLabel.setTextFill(Color.web("#64748b"));

                                Region rSpacer = new Region();
                                HBox.setHgrow(rSpacer, Priority.ALWAYS);

                                Label jDate = new Label("Joined: " + s.get("joinedAt"));
                                jDate.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
                                jDate.setTextFill(Color.web("#94a3b8"));

                                sRow.getChildren().addAll(uLabel, new Label(" • "), eLabel, rSpacer, jDate);
                                rosterBox.getChildren().add(sRow);
                            }
                        }

                        classCard.getChildren().addAll(topRow, rosterBox);
                        classesListView.getChildren().add(classCard);
                    }
                }
            } catch (SQLException ex) {
                Label err = new Label("Error loading classes: " + ex.getMessage());
                err.setTextFill(Color.web("#ef4444"));
                classesListView.getChildren().add(err);
            }
        };

        refreshList.run();

        createBtn.setOnAction(e -> {
            String name = classNameField.getText().trim();
            String code = classCodeField.getText().trim().toUpperCase();

            if (name.isEmpty() || code.isEmpty()) {
                showMessage(statusMsgLabel, "Please enter both Class Name and Class Code.", false);
                return;
            }

            if (code.length() != 6 || !code.matches("^[a-zA-Z0-9]{6}$")) {
                showMessage(statusMsgLabel, "Class code must be exactly 6 alphanumeric characters (letters and numbers only).", false);
                return;
            }

            try {
                classDAO.createClass(user.getId(), name, code);
                showMessage(statusMsgLabel, "Class '" + name + "' created successfully with code: " + code, true);
                classNameField.clear();
                classCodeField.clear();
                refreshList.run();

            } catch (ClassDAO.DuplicateClassCodeException ex) {
                showMessage(statusMsgLabel, ex.getMessage(), false);
            } catch (SQLException ex) {
                showMessage(statusMsgLabel, "Database Error: " + ex.getMessage(), false);
            }
        });

        listCard.getChildren().addAll(title2, classesListView);

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showTeacherDashboard(stage, user, null));

        rootBox.getChildren().addAll(headerBox, statusMsgLabel, createCard, listCard, backBtn);

        StackPane centerContainer = new StackPane(rootBox);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 780, 700);
    }

    /**
     * SEPARATE SCREEN: TEACHER MY TESTS SCREEN
     */
    private void showTeacherTestsScreen(Stage stage, User user) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(580);
        rootBox.setAlignment(Pos.CENTER);

        Label header = new Label("My Created Tests");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        header.setTextFill(Color.web("#0d9488"));

        VBox testsListView = new VBox(12);

        try {
            List<Map<String, Object>> tests = testDAO.getTestsByTeacher(user.getId());
            if (tests.isEmpty()) {
                Label empty = new Label("You haven't created any tests yet.");
                empty.setTextFill(Color.web("#94a3b8"));
                testsListView.getChildren().add(empty);
            } else {
                for (Map<String, Object> t : tests) {
                    VBox card = new VBox(8);
                    card.setPadding(new Insets(16));
                    card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8;");

                    boolean isPublic = Boolean.TRUE.equals(t.get("isPublic"));
                    String className = (String) t.get("className");

                    Label titleLabel = new Label((String) t.get("title"));
                    titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
                    titleLabel.setTextFill(Color.web("#1e293b"));

                    Label badge = new Label(isPublic ? "🌐 Public Test" : "🔒 Private (Class: " + (className != null ? className : "Assigned") + ")");
                    badge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                    badge.setTextFill(Color.web(isPublic ? "#16a34a" : "#0284c7"));

                    HBox topBox = new HBox(12, titleLabel, badge);
                    topBox.setAlignment(Pos.CENTER_LEFT);

                    int durationMins = toInt(t.get("totalTimeSeconds")) / 60;
                    Label detailsLabel = new Label(String.format("Language: %s | Duration: %d mins | Created: %s",
                            t.get("language"), durationMins, t.get("createdAt")));
                    detailsLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
                    detailsLabel.setTextFill(Color.web("#64748b"));

                    int testId = toInt(t.get("id"));
                    Button viewSubmissionsBtn = new Button("📊 View Submissions →");
                    viewSubmissionsBtn.setStyle("-fx-background-color: #d97706; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px; -fx-background-radius: 5; -fx-cursor: hand;");
                    viewSubmissionsBtn.setOnAction(e -> showTeacherSubmissionsScreen(stage, user, testId));

                    HBox bottomBox = new HBox(viewSubmissionsBtn);
                    bottomBox.setAlignment(Pos.CENTER_RIGHT);

                    card.getChildren().addAll(topBox, detailsLabel, bottomBox);
                    testsListView.getChildren().add(card);
                }
            }
        } catch (SQLException ex) {
            Label err = new Label("Failed to load tests: " + ex.getMessage());
            err.setTextFill(Color.web("#ef4444"));
            testsListView.getChildren().add(err);
        }

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showTeacherDashboard(stage, user, null));

        rootBox.getChildren().addAll(header, testsListView, backBtn);

        StackPane centerContainer = new StackPane(rootBox);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 750, 680);
    }

    /**
     * SEPARATE SCREEN: TEACHER VIEW STUDENT SUBMISSIONS SCREEN
     */
    private void showTeacherSubmissionsScreen(Stage stage, User user, Integer filterTestId) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(620);
        rootBox.setAlignment(Pos.CENTER);

        Label header = new Label("Student Test Submissions");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        header.setTextFill(Color.web("#d97706"));

        VBox submissionsListView = new VBox(12);

        try {
            List<Map<String, Object>> submissions = resultDAO.getSubmissionsForTeacher(user.getId(), filterTestId);
            if (submissions.isEmpty()) {
                Label empty = new Label("No student submissions found.");
                empty.setTextFill(Color.web("#94a3b8"));
                submissionsListView.getChildren().add(empty);
            } else {
                for (Map<String, Object> sub : submissions) {
                    VBox card = new VBox(6);
                    card.setPadding(new Insets(14));
                    card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8;");

                    Label tTitle = new Label("Test: " + sub.get("testTitle"));
                    tTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
                    tTitle.setTextFill(Color.web("#1e293b"));

                    Label sName = new Label("Student: " + sub.get("studentName") + " (" + sub.get("studentEmail") + ")");
                    sName.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 13));
                    sName.setTextFill(Color.web("#475569"));

                    int score = toInt(sub.get("score"));
                    int total = toInt(sub.get("total"));
                    double perc = total > 0 ? ((double) score / total) * 100 : 0;
                    int overtime = toInt(sub.get("overtimeSeconds"));

                    Label scoreLabel = new Label(String.format("Score: %d / %d (%.1f%%) | Date: %s", score, total, perc, sub.get("takenAt")));
                    scoreLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
                    scoreLabel.setTextFill(Color.web("#10b981"));

                    card.getChildren().addAll(tTitle, sName, scoreLabel);
                    if (overtime > 0) {
                        Label otLabel = new Label("Overtime: " + overtime + "s");
                        otLabel.setTextFill(Color.web("#ef4444"));
                        card.getChildren().add(otLabel);
                    }
                    submissionsListView.getChildren().add(card);
                }
            }
        } catch (SQLException ex) {
            Label err = new Label("Failed to load submissions: " + ex.getMessage());
            err.setTextFill(Color.web("#ef4444"));
            submissionsListView.getChildren().add(err);
        }

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showTeacherDashboard(stage, user, null));

        rootBox.getChildren().addAll(header, submissionsListView, backBtn);

        StackPane centerContainer = new StackPane(rootBox);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 750, 680);
    }

    /**
     * WIZARD STEP 1: Test Details Screen (Title, Language, Duration, MCQ count, Expiry Action, Public vs Class Visibility).
     */
    private void showWizardStep1TestDetails(Stage stage, User user, 
                                           DraftTestDetails draftTest, 
                                           List<DraftQuestion> draftQuestions) {
        VBox form = new VBox(14);
        form.setMaxWidth(460);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(26));
        form.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 16, 0, 0, 4);");

        Label headerLabel = new Label("Create Test - Step 1 of 2");
        headerLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        headerLabel.setTextFill(Color.web("#8e44ad"));

        Label subtitleLabel = new Label("Enter overall test details and visibility below");
        subtitleLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
        subtitleLabel.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(4, headerLabel, subtitleLabel);
        headerBox.setAlignment(Pos.CENTER);

        TextField titleField = new TextField(draftTest.title);
        titleField.setPromptText("Test Title (e.g. Java Basics Quiz)");
        titleField.setStyle(INPUT_STYLE);
        titleField.setPrefHeight(42);

        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll("English", "Urdu");
        langCombo.setValue(draftTest.language != null ? draftTest.language : "English");
        langCombo.setMaxWidth(Double.MAX_VALUE);
        langCombo.setStyle(COMBO_STYLE);
        langCombo.setPrefHeight(42);

        TextField timeMinutesField = new TextField(draftTest.timeMinutes > 0 ? String.valueOf(draftTest.timeMinutes) : "15");
        timeMinutesField.setPromptText("Total Time in Minutes (e.g. 15)");
        timeMinutesField.setStyle(INPUT_STYLE);
        timeMinutesField.setPrefHeight(42);

        TextField mcqCountField = new TextField(draftTest.targetQuestionCount > 0 ? String.valueOf(draftTest.targetQuestionCount) : "5");
        mcqCountField.setPromptText("Number of MCQs to add (e.g. 5)");
        mcqCountField.setStyle(INPUT_STYLE);
        mcqCountField.setPrefHeight(42);

        ComboBox<String> expiryCombo = new ComboBox<>();
        expiryCombo.getItems().addAll("Auto-submit when time ends", "Allow overtime");
        if ("ALLOW_OVERTIME".equals(draftTest.expiryAction)) {
            expiryCombo.setValue("Allow overtime");
        } else {
            expiryCombo.setValue("Auto-submit when time ends");
        }
        expiryCombo.setMaxWidth(Double.MAX_VALUE);
        expiryCombo.setStyle(COMBO_STYLE);
        expiryCombo.setPrefHeight(42);

        // Visibility Options: Public vs Assign to Class (Clean, visible labels)
        Label visLabel = new Label("Test Visibility:");
        visLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        visLabel.setTextFill(Color.web("#334155"));

        RadioButton publicRadio = new RadioButton("Public (all students)");
        publicRadio.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        publicRadio.setStyle("-fx-text-fill: #1e293b;");

        RadioButton classRadio = new RadioButton("Private (assign to class)");
        classRadio.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        classRadio.setStyle("-fx-text-fill: #1e293b;");

        ToggleGroup visGroup = new ToggleGroup();
        publicRadio.setToggleGroup(visGroup);
        classRadio.setToggleGroup(visGroup);

        if (draftTest.isPublic) {
            publicRadio.setSelected(true);
        } else {
            classRadio.setSelected(true);
        }

        HBox visBox = new HBox(16, publicRadio, classRadio);
        visBox.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> classCombo = new ComboBox<>();
        classCombo.setPromptText("Select a Class...");
        classCombo.setMaxWidth(Double.MAX_VALUE);
        classCombo.setStyle(COMBO_STYLE);
        classCombo.setPrefHeight(42);
        classCombo.setDisable(draftTest.isPublic);

        Map<String, Integer> classMap = new HashMap<>();
        try {
            List<Map<String, Object>> teacherClasses = classDAO.getClassesByTeacher(user.getId());
            for (Map<String, Object> c : teacherClasses) {
                String displayName = c.get("className") + " (" + c.get("classCode") + ")";
                classCombo.getItems().add(displayName);
                classMap.put(displayName, (Integer) c.get("id"));
                if (draftTest.classId != null && draftTest.classId.equals(c.get("id"))) {
                    classCombo.setValue(displayName);
                }
            }
        } catch (SQLException ex) {
            // Ignore error
        }

        publicRadio.setOnAction(e -> classCombo.setDisable(true));
        classRadio.setOnAction(e -> classCombo.setDisable(false));

        VBox visContainer = new VBox(8, visLabel, visBox, classCombo);
        visContainer.setStyle("-fx-background-color: #f8fafc; -fx-padding: 10 14; -fx-background-radius: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 6;");

        // Question Order Options: Sequential vs Shuffled
        Label orderLabel = new Label("Question Order:");
        orderLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        orderLabel.setTextFill(Color.web("#334155"));

        RadioButton seqRadio = new RadioButton("Sequential (in order)");
        seqRadio.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        seqRadio.setStyle("-fx-text-fill: #1e293b;");

        RadioButton shufRadio = new RadioButton("Shuffled (random per student)");
        shufRadio.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        shufRadio.setStyle("-fx-text-fill: #1e293b;");

        ToggleGroup orderGroup = new ToggleGroup();
        seqRadio.setToggleGroup(orderGroup);
        shufRadio.setToggleGroup(orderGroup);

        if ("SHUFFLED".equalsIgnoreCase(draftTest.questionOrder)) {
            shufRadio.setSelected(true);
        } else {
            seqRadio.setSelected(true);
        }

        HBox orderBox = new HBox(16, seqRadio, shufRadio);
        orderBox.setAlignment(Pos.CENTER_LEFT);

        Label orderHelpLabel = new Label("Sequential shows questions in the created order. Shuffled randomizes questions independently for each student.");
        orderHelpLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        orderHelpLabel.setTextFill(Color.web("#64748b"));
        orderHelpLabel.setWrapText(true);

        VBox orderContainer = new VBox(8, orderLabel, orderBox, orderHelpLabel);
        orderContainer.setStyle("-fx-background-color: #f8fafc; -fx-padding: 10 14; -fx-background-radius: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 6;");

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);

        Button backToDashBtn = new Button("Cancel");
        backToDashBtn.setPrefWidth(120);
        backToDashBtn.setPrefHeight(44);
        backToDashBtn.setStyle("-fx-background-color: #94a3b8; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backToDashBtn.setOnAction(e -> showTeacherDashboard(stage, user, null));

        Button nextBtn = new Button("Next →");
        nextBtn.setPrefWidth(260);
        nextBtn.setPrefHeight(44);
        nextBtn.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        HBox btnBox = new HBox(12, backToDashBtn, nextBtn);
        btnBox.setAlignment(Pos.CENTER);

        nextBtn.setOnAction(e -> {
            String title = titleField.getText().trim();
            String lang = langCombo.getValue();
            String timeStr = timeMinutesField.getText().trim();
            String mcqStr = mcqCountField.getText().trim();
            String expiryStr = expiryCombo.getValue();

            if (title.isEmpty() || timeStr.isEmpty() || mcqStr.isEmpty()) {
                showMessage(messageLabel, "Please fill in all required fields.", false);
                return;
            }

            int minutes;
            try {
                minutes = Integer.parseInt(timeStr);
                if (minutes <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                showMessage(messageLabel, "Please enter a valid positive number for minutes.", false);
                return;
            }

            int mcqCount;
            try {
                mcqCount = Integer.parseInt(mcqStr);
                if (mcqCount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                showMessage(messageLabel, "Please enter a valid positive number for MCQs count.", false);
                return;
            }

            boolean isPub = publicRadio.isSelected();
            Integer selectedClassId = null;

            if (!isPub) {
                String selectedDisplay = classCombo.getValue();
                if (selectedDisplay == null || !classMap.containsKey(selectedDisplay)) {
                    showMessage(messageLabel, "Please select a valid class to assign this private test.", false);
                    return;
                }
                selectedClassId = classMap.get(selectedDisplay);
            }

            draftTest.title = title;
            draftTest.language = lang;
            draftTest.timeMinutes = minutes;
            draftTest.targetQuestionCount = mcqCount;
            draftTest.expiryAction = "Auto-submit when time ends".equals(expiryStr) ? "AUTO_SUBMIT" : "ALLOW_OVERTIME";
            draftTest.isPublic = isPub;
            draftTest.classId = selectedClassId;
            draftTest.questionOrder = shufRadio.isSelected() ? "SHUFFLED" : "SEQUENTIAL";

            showWizardStep2QuestionScreen(stage, user, draftTest, draftQuestions, 0);
        });

        form.getChildren().addAll(
                headerBox,
                new Label("Test Title:"), titleField,
                new Label("Language:"), langCombo,
                new Label("Duration (Minutes):"), timeMinutesField,
                new Label("Target Number of Questions:"), mcqCountField,
                new Label("Expiry Action:"), expiryCombo,
                visContainer,
                orderContainer,
                messageLabel,
                btnBox
        );

        StackPane centerContainer = new StackPane(form);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 750, 700);
    }

    /**
     * WIZARD STEP 2: One Question Per Page Screen.
     */
    private void showWizardStep2QuestionScreen(Stage stage, User user, 
                                                DraftTestDetails draftTest, 
                                                List<DraftQuestion> draftQuestions, 
                                                int questionIndex) {
        VBox form = new VBox(14);
        form.setMaxWidth(580);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(26));
        form.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 16, 0, 0, 4);");

        boolean isUrdu = "Urdu".equalsIgnoreCase(draftTest.language);
        NodeOrientation textOrientation = isUrdu ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;

        int displayNum = questionIndex + 1;
        int totalTarget = Math.max(draftTest.targetQuestionCount, draftQuestions.size());

        Label progressLabel = new Label("Question " + displayNum + " of " + totalTarget);
        progressLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        progressLabel.setTextFill(Color.web("#8e44ad"));

        Label testTitleBadge = new Label("Test: " + draftTest.title + " (" + draftTest.language + ")");
        testTitleBadge.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 13));
        testTitleBadge.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(4, progressLabel, testTitleBadge);
        headerBox.setAlignment(Pos.CENTER);

        DraftQuestion currentQuestion;
        if (questionIndex < draftQuestions.size()) {
            currentQuestion = draftQuestions.get(questionIndex);
        } else {
            currentQuestion = new DraftQuestion();
        }

        TextField questionTextField = new TextField(currentQuestion.questionText);
        questionTextField.setPromptText(isUrdu ? "سوال (Question Text)" : "Question Text");
        questionTextField.setStyle(INPUT_STYLE);
        questionTextField.setPrefHeight(42);
        questionTextField.setNodeOrientation(textOrientation);

        TextField optAField = new TextField(currentQuestion.optionA);
        optAField.setPromptText(isUrdu ? "آپشن A" : "Option A");
        optAField.setStyle(INPUT_STYLE);
        optAField.setPrefHeight(38);
        optAField.setNodeOrientation(textOrientation);

        TextField optBField = new TextField(currentQuestion.optionB);
        optBField.setPromptText(isUrdu ? "آپشن B" : "Option B");
        optBField.setStyle(INPUT_STYLE);
        optBField.setPrefHeight(38);
        optBField.setNodeOrientation(textOrientation);

        TextField optCField = new TextField(currentQuestion.optionC);
        optCField.setPromptText(isUrdu ? "آپشن C" : "Option C");
        optCField.setStyle(INPUT_STYLE);
        optCField.setPrefHeight(38);
        optCField.setNodeOrientation(textOrientation);

        TextField optDField = new TextField(currentQuestion.optionD);
        optDField.setPromptText(isUrdu ? "آپشن D" : "Option D");
        optDField.setStyle(INPUT_STYLE);
        optDField.setPrefHeight(38);
        optDField.setNodeOrientation(textOrientation);

        ComboBox<String> correctOptCombo = new ComboBox<>();
        correctOptCombo.getItems().addAll("Option A", "Option B", "Option C", "Option D");
        
        String selectedOptLabel = "Option A";
        if ("B".equals(currentQuestion.correctOption)) selectedOptLabel = "Option B";
        else if ("C".equals(currentQuestion.correctOption)) selectedOptLabel = "Option C";
        else if ("D".equals(currentQuestion.correctOption)) selectedOptLabel = "Option D";
        correctOptCombo.setValue(selectedOptLabel);
        correctOptCombo.setMaxWidth(Double.MAX_VALUE);
        correctOptCombo.setStyle(COMBO_STYLE);
        correctOptCombo.setPrefHeight(38);

        TextField topicField = new TextField(currentQuestion.topic);
        topicField.setPromptText("Topic (e.g. Loops, OOP)");
        topicField.setStyle(INPUT_STYLE);
        topicField.setPrefHeight(38);

        ComboBox<String> difficultyCombo = new ComboBox<>();
        difficultyCombo.getItems().addAll("EASY", "MEDIUM", "HARD");
        difficultyCombo.setValue(currentQuestion.difficulty != null ? currentQuestion.difficulty : "MEDIUM");
        difficultyCombo.setMaxWidth(Double.MAX_VALUE);
        difficultyCombo.setStyle(COMBO_STYLE);
        difficultyCombo.setPrefHeight(38);

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);

        Button backBtn = new Button("← Back");
        backBtn.setPrefWidth(140);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        Button nextBtn = new Button("Next Question →");
        nextBtn.setPrefWidth(190);
        nextBtn.setPrefHeight(44);
        nextBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        Button finishBtn = new Button("✓ Finish & Save Test");
        finishBtn.setPrefWidth(210);
        finishBtn.setPrefHeight(44);
        finishBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        HBox navBox = new HBox(10, backBtn, nextBtn, finishBtn);
        navBox.setAlignment(Pos.CENTER);

        Runnable saveCurrentQuestionToMemory = () -> {
            currentQuestion.questionText = questionTextField.getText().trim();
            currentQuestion.optionA = optAField.getText().trim();
            currentQuestion.optionB = optBField.getText().trim();
            currentQuestion.optionC = optCField.getText().trim();
            currentQuestion.optionD = optDField.getText().trim();

            String sel = correctOptCombo.getValue();
            if ("Option B".equals(sel)) currentQuestion.correctOption = "B";
            else if ("Option C".equals(sel)) currentQuestion.correctOption = "C";
            else if ("Option D".equals(sel)) currentQuestion.correctOption = "D";
            else currentQuestion.correctOption = "A";

            currentQuestion.topic = topicField.getText().trim().isEmpty() ? "General" : topicField.getText().trim();
            currentQuestion.difficulty = difficultyCombo.getValue();

            if (questionIndex < draftQuestions.size()) {
                draftQuestions.set(questionIndex, currentQuestion);
            } else {
                draftQuestions.add(currentQuestion);
            }
        };

        backBtn.setOnAction(e -> {
            if (!questionTextField.getText().trim().isEmpty()) {
                saveCurrentQuestionToMemory.run();
            }

            if (questionIndex == 0) {
                showWizardStep1TestDetails(stage, user, draftTest, draftQuestions);
            } else {
                showWizardStep2QuestionScreen(stage, user, draftTest, draftQuestions, questionIndex - 1);
            }
        });

        nextBtn.setOnAction(e -> {
            String qText = questionTextField.getText().trim();
            String optA = optAField.getText().trim();
            String optB = optBField.getText().trim();
            String optC = optCField.getText().trim();
            String optD = optDField.getText().trim();

            if (qText.isEmpty() || optA.isEmpty() || optB.isEmpty() || optC.isEmpty() || optD.isEmpty()) {
                showMessage(messageLabel, "Please enter question text and all 4 options before proceeding.", false);
                return;
            }

            saveCurrentQuestionToMemory.run();
            showWizardStep2QuestionScreen(stage, user, draftTest, draftQuestions, questionIndex + 1);
        });

        finishBtn.setOnAction(e -> {
            String qText = questionTextField.getText().trim();
            String optA = optAField.getText().trim();
            String optB = optBField.getText().trim();

            if (!qText.isEmpty() && !optA.isEmpty() && !optB.isEmpty()) {
                saveCurrentQuestionToMemory.run();
            }

            if (draftQuestions.isEmpty()) {
                showMessage(messageLabel, "Please add at least 1 valid question before finishing.", false);
                return;
            }

            try {
                int totalTimeSeconds = draftTest.timeMinutes * 60;
                int testId = testDAO.createTest(
                        draftTest.title, draftTest.language, totalTimeSeconds, 
                        draftTest.expiryAction, user.getId(),
                        draftTest.classId, draftTest.isPublic, draftTest.questionOrder
                );

                for (DraftQuestion q : draftQuestions) {
                    testDAO.addQuestion(
                            testId, q.questionText, q.optionA, q.optionB, q.optionC, q.optionD,
                            q.correctOption, q.topic, q.difficulty
                    );
                }

                String successMsg = String.format("Test '%s' created successfully with %d questions!", 
                        draftTest.title, draftQuestions.size());
                showTeacherDashboard(stage, user, successMsg);

            } catch (SQLException ex) {
                showMessage(messageLabel, "Failed to save test to database: " + ex.getMessage(), false);
            }
        });

        form.getChildren().addAll(
                headerBox,
                questionTextField,
                optAField, optBField, optCField, optDField,
                new Label("Correct Option:"), correctOptCombo,
                topicField,
                new Label("Difficulty:"), difficultyCombo,
                messageLabel,
                navBox
        );

        StackPane centerContainer = new StackPane(form);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 750, 700);
    }

    // =========================================================================
    // STUDENT SIDE: HUB & SEPARATE SCREENS
    // =========================================================================

    /**
     * STUDENT DASHBOARD HUB SCREEN
     */
    private void showStudentDashboard(Stage stage, User user) {
        VBox card = new VBox(22);
        card.setMaxWidth(540);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPadding(new Insets(34, 38, 34, 38));
        card.setAlignment(Pos.CENTER);
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 18; -fx-background-radius: 18; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 20, 0, 0, 6);");

        Label header = new Label("Student Hub");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
        header.setTextFill(Color.web("#047857"));

        Label welcomeMsg = new Label("Welcome, " + user.getUsername() + "!");
        welcomeMsg.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        welcomeMsg.setTextFill(Color.web("#1e293b"));

        Label infoLabel = new Label("Email: " + user.getEmail() + " | ID: " + user.getId());
        infoLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 14));
        infoLabel.setTextFill(Color.web("#64748b"));

        VBox profileBox = new VBox(6, header, welcomeMsg, infoLabel);
        profileBox.setAlignment(Pos.CENTER);

        // Navigation Menu Buttons (Lighter Background Theme, Dark Text, Larger Size)
        Button availableTestsBtn = new Button("📝  Available Tests");
        availableTestsBtn.setMaxWidth(Double.MAX_VALUE);
        availableTestsBtn.setPrefHeight(56);
        availableTestsBtn.setStyle("-fx-background-color: #d1fae5; -fx-border-color: #6ee7b7; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #065f46; -fx-font-weight: bold; -fx-font-size: 17px; -fx-cursor: hand;");
        availableTestsBtn.setOnAction(e -> showStudentAvailableTestsScreen(stage, user));

        Button joinClassBtn = new Button("➕  Join a Class");
        joinClassBtn.setMaxWidth(Double.MAX_VALUE);
        joinClassBtn.setPrefHeight(56);
        joinClassBtn.setStyle("-fx-background-color: #e0f2fe; -fx-border-color: #7dd3fc; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #0369a1; -fx-font-weight: bold; -fx-font-size: 17px; -fx-cursor: hand;");
        joinClassBtn.setOnAction(e -> showStudentJoinClassScreen(stage, user));

        Button myResultsBtn = new Button("📊  My Results History");
        myResultsBtn.setMaxWidth(Double.MAX_VALUE);
        myResultsBtn.setPrefHeight(56);
        myResultsBtn.setStyle("-fx-background-color: #f3e8ff; -fx-border-color: #d8b4fe; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #581c87; -fx-font-weight: bold; -fx-font-size: 17px; -fx-cursor: hand;");
        myResultsBtn.setOnAction(e -> showStudentResultsScreen(stage, user));

        Button logoutButton = new Button("🚪  Logout");
        logoutButton.setMaxWidth(Double.MAX_VALUE);
        logoutButton.setPrefHeight(50);
        logoutButton.setStyle("-fx-background-color: #ffe4e6; -fx-border-color: #fecdd3; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #be123c; -fx-font-weight: bold; -fx-font-size: 15px; -fx-cursor: hand;");
        logoutButton.setOnAction(e -> showLoginScreen(stage));

        VBox menuBox = new VBox(14, availableTestsBtn, joinClassBtn, myResultsBtn, logoutButton);

        card.getChildren().addAll(profileBox, menuBox);

        StackPane rootContainer = createCenteredRoot(card);
        setScreenRoot(stage, rootContainer, 750, 680);
    }

    /**
     * SEPARATE SCREEN: STUDENT AVAILABLE TESTS SCREEN
     */
    private void showStudentAvailableTestsScreen(Stage stage, User user) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(560);
        rootBox.setAlignment(Pos.CENTER);

        Label header = new Label("Available Tests");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        header.setTextFill(Color.web("#10b981"));

        VBox availableTestsBox = new VBox(12);

        try {
            List<Map<String, Object>> tests = testDAO.getAllTestsForStudent(user.getId());
            if (tests.isEmpty()) {
                Label emptyLabel = new Label("No tests available for you at the moment.");
                emptyLabel.setTextFill(Color.web("#94a3b8"));
                availableTestsBox.getChildren().add(emptyLabel);
            } else {
                for (Map<String, Object> t : tests) {
                    int durationMinutes = toInt(t.get("totalTimeSeconds")) / 60;
                    boolean isPublic = Boolean.TRUE.equals(t.get("isPublic"));
                    String className = (String) t.get("className");

                    VBox testCard = new VBox(8);
                    testCard.setPadding(new Insets(16));
                    testCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8;");

                    Label tTitle = new Label((String) t.get("title"));
                    tTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
                    tTitle.setTextFill(Color.web("#1e293b"));

                    Label badge = new Label(isPublic ? "🌐 Public" : "🏫 Class: " + (className != null ? className : "Enrolled"));
                    badge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                    badge.setTextFill(Color.web(isPublic ? "#16a34a" : "#0284c7"));

                    HBox topHeader = new HBox(12, tTitle, badge);
                    topHeader.setAlignment(Pos.CENTER_LEFT);

                    Label tDetails = new Label(String.format("Language: %s | Duration: %d mins | By: %s",
                            t.get("language"), durationMinutes, t.get("teacherName")));
                    tDetails.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
                    tDetails.setTextFill(Color.web("#64748b"));

                    Button startBtn = new Button("Start Test →");
                    startBtn.setPrefHeight(36);
                    startBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-background-radius: 5; -fx-cursor: hand;");
                    startBtn.setOnAction(e -> showTestTakingScreen(stage, user, t));

                    HBox cardBottom = new HBox(startBtn);
                    cardBottom.setAlignment(Pos.CENTER_RIGHT);

                    testCard.getChildren().addAll(topHeader, tDetails, cardBottom);
                    availableTestsBox.getChildren().add(testCard);
                }
            }
        } catch (SQLException ex) {
            Label errLabel = new Label("Failed to load tests: " + ex.getMessage());
            errLabel.setTextFill(Color.web("#ef4444"));
            availableTestsBox.getChildren().add(errLabel);
        }

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showStudentDashboard(stage, user));

        rootBox.getChildren().addAll(header, availableTestsBox, backBtn);

        StackPane centerContainer = new StackPane(rootBox);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 750, 650);
    }

    /**
     * SEPARATE SCREEN: STUDENT JOIN A CLASS SCREEN
     */
    private void showStudentJoinClassScreen(Stage stage, User user) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(520);
        rootBox.setAlignment(Pos.CENTER);

        // Card 1: Join Form
        VBox joinCard = new VBox(14);
        joinCard.setPadding(new Insets(20));
        joinCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 10; -fx-background-radius: 10;");

        Label title1 = new Label("➕ Join a Teacher's Class");
        title1.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        title1.setTextFill(Color.web("#0284c7"));

        TextField teacherUsernameField = new TextField();
        teacherUsernameField.setPromptText("Teacher's Username (e.g. prof_smith)");
        teacherUsernameField.setStyle(INPUT_STYLE);
        teacherUsernameField.setPrefHeight(42);

        TextField classCodeField = new TextField();
        classCodeField.setPromptText("6-Character Class Code (e.g. K9X2P4)");
        classCodeField.setStyle(INPUT_STYLE);
        classCodeField.setPrefHeight(42);

        Label joinMsg = new Label();
        joinMsg.setWrapText(true);

        Button joinBtn = new Button("Join Class");
        joinBtn.setMaxWidth(Double.MAX_VALUE);
        joinBtn.setPrefHeight(42);
        joinBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        joinCard.getChildren().addAll(title1, teacherUsernameField, classCodeField, joinBtn, joinMsg);

        // Card 2: Enrolled Classes List
        VBox listCard = new VBox(12);
        listCard.setPadding(new Insets(20));
        listCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 10; -fx-background-radius: 10;");

        Label title2 = new Label("🏫 My Enrolled Classes:");
        title2.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        title2.setTextFill(Color.web("#1e293b"));

        VBox enrolledListView = new VBox(10);

        Runnable refreshEnrolled = () -> {
            enrolledListView.getChildren().clear();
            try {
                List<Map<String, Object>> enrolled = classDAO.getEnrolledClassesForStudent(user.getId());
                if (enrolled.isEmpty()) {
                    Label empty = new Label("You are not enrolled in any classes yet.");
                    empty.setTextFill(Color.web("#94a3b8"));
                    enrolledListView.getChildren().add(empty);
                } else {
                    for (Map<String, Object> c : enrolled) {
                        VBox item = new VBox(4);
                        item.setPadding(new Insets(10, 12, 10, 12));
                        item.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6;");

                        Label cName = new Label((String) c.get("className"));
                        cName.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
                        cName.setTextFill(Color.web("#1e293b"));

                        Label cDetails = new Label("Teacher: " + c.get("teacherName") + " | Code: " + c.get("classCode"));
                        cDetails.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
                        cDetails.setTextFill(Color.web("#64748b"));

                        item.getChildren().addAll(cName, cDetails);
                        enrolledListView.getChildren().add(item);
                    }
                }
            } catch (SQLException ex) {
                Label err = new Label("Error loading enrolled classes: " + ex.getMessage());
                err.setTextFill(Color.web("#ef4444"));
                enrolledListView.getChildren().add(err);
            }
        };

        refreshEnrolled.run();

        joinBtn.setOnAction(e -> {
            String tUser = teacherUsernameField.getText().trim();
            String cCode = classCodeField.getText().trim();

            if (tUser.isEmpty() || cCode.isEmpty()) {
                showMessage(joinMsg, "Please enter both teacher username and class code.", false);
                return;
            }

            try {
                classDAO.joinClass(user.getId(), tUser, cCode);
                showMessage(joinMsg, "Successfully enrolled in class!", true);
                teacherUsernameField.clear();
                classCodeField.clear();
                refreshEnrolled.run();

            } catch (IllegalArgumentException | IllegalStateException ex) {
                showMessage(joinMsg, ex.getMessage(), false);
            } catch (SQLException ex) {
                showMessage(joinMsg, "Database Error: " + ex.getMessage(), false);
            }
        });

        listCard.getChildren().addAll(title2, enrolledListView);

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showStudentDashboard(stage, user));

        rootBox.getChildren().addAll(joinCard, listCard, backBtn);

        StackPane centerContainer = new StackPane(rootBox);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 750, 680);
    }

    /**
     * SEPARATE SCREEN: STUDENT RESULTS HISTORY SCREEN
     */
    private void showStudentResultsScreen(Stage stage, User user) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(520);
        rootBox.setAlignment(Pos.CENTER);

        Label header = new Label("My Test Results");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));
        header.setTextFill(Color.web("#8e44ad"));

        Label sub = new Label("Results history for " + user.getUsername());
        sub.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
        sub.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(4, header, sub);
        headerBox.setAlignment(Pos.CENTER);

        VBox resultsListBox = new VBox(12);
        resultsListBox.setAlignment(Pos.TOP_LEFT);

        try {
            List<Map<String, Object>> results = resultDAO.getResultsByStudent(user.getId());
            if (results.isEmpty()) {
                Label emptyLabel = new Label("You haven't taken any tests yet.");
                emptyLabel.setTextFill(Color.web("#94a3b8"));
                resultsListBox.getChildren().add(emptyLabel);
            } else {
                for (Map<String, Object> r : results) {
                    VBox rCard = new VBox(6);
                    rCard.setPadding(new Insets(12, 14, 12, 14));
                    rCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8;");

                    Label rTitle = new Label((String) r.get("testTitle"));
                    rTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
                    rTitle.setTextFill(Color.web("#1e293b"));

                    int score = toInt(r.get("score"));
                    int total = toInt(r.get("total"));
                    int overtime = toInt(r.get("overtimeSeconds"));

                    Label rScore = new Label(String.format("Score: %d / %d  |  Taken At: %s", score, total, r.get("takenAt")));
                    rScore.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 13));
                    rScore.setTextFill(Color.web("#10b981"));

                    rCard.getChildren().addAll(rTitle, rScore);
                    if (overtime > 0) {
                        Label otLabel = new Label("Overtime: " + overtime + "s");
                        otLabel.setTextFill(Color.web("#ef4444"));
                        rCard.getChildren().add(otLabel);
                    }
                    resultsListBox.getChildren().add(rCard);
                }
            }
        } catch (SQLException ex) {
            Label errLabel = new Label("Failed to load results: " + ex.getMessage());
            errLabel.setTextFill(Color.web("#ef4444"));
            resultsListBox.getChildren().add(errLabel);
        }

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showStudentDashboard(stage, user));

        rootBox.getChildren().addAll(headerBox, resultsListBox, backBtn);

        StackPane centerContainer = new StackPane(rootBox);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 750, 650);
    }

    // =========================================================================
    // TEST-TAKING & RESULT DISPLAY SCREENS
    // =========================================================================

    /**
     * TEST-TAKING SCREEN
     */
    private void showTestTakingScreen(Stage stage, User user, Map<String, Object> testMap) {
        int testId = toInt(testMap.get("id"));
        String testTitle = (String) testMap.get("title");
        String language = (String) testMap.get("language");
        int totalTimeSeconds = toInt(testMap.get("totalTimeSeconds"));
        String expiryAction = (String) testMap.get("expiryAction");

        boolean isUrdu = "Urdu".equalsIgnoreCase(language);
        NodeOrientation textOrientation = isUrdu ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;

        List<Map<String, Object>> fetched;
        try {
            fetched = testDAO.getQuestionsByTestId(testId);
        } catch (SQLException ex) {
            showStudentDashboard(stage, user);
            return;
        }

        if (fetched.isEmpty()) {
            VBox emptyBox = new VBox(20);
            emptyBox.setAlignment(Pos.CENTER);
            Label err = new Label("This test does not contain any questions yet.");
            err.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 15));
            Button backBtn = new Button("Back to Dashboard");
            backBtn.setOnAction(e -> showStudentDashboard(stage, user));
            emptyBox.getChildren().addAll(err, backBtn);
            StackPane root = createCenteredRoot(emptyBox);
            setScreenRoot(stage, root, 750, 550);
            return;
        }

        // Requirement 4: Shuffled question order per student attempt
        List<Map<String, Object>> preparedList = new ArrayList<>(fetched);
        String questionOrder = (String) testMap.get("questionOrder");
        if ("SHUFFLED".equalsIgnoreCase(questionOrder) && preparedList.size() > 1) {
            Collections.shuffle(preparedList);
        }
        final List<Map<String, Object>> questions = Collections.unmodifiableList(preparedList);

        Map<Integer, String> studentAnswers = new HashMap<>();
        int[] remainingSecondsHolder = new int[]{ totalTimeSeconds };
        int[] overtimeSecondsHolder = new int[]{ 0 };
        boolean[] isOvertimeActive = new boolean[]{ false };
        boolean[] isSubmittedHolder = new boolean[]{ false };

        Label timerLabel = new Label();
        timerLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));

        Timeline timerTimeline = new Timeline();
        timerTimeline.setCycleCount(Timeline.INDEFINITE);

        Runnable updateTimerDisplay = () -> {
            if (!isOvertimeActive[0]) {
                int secs = remainingSecondsHolder[0];
                int m = secs / 60;
                int s = secs % 60;
                timerLabel.setText(String.format("⏱ Time Remaining: %02d:%02d", m, s));
                timerLabel.setTextFill(Color.web("#0284c7"));
            } else {
                int secs = overtimeSecondsHolder[0];
                int m = secs / 60;
                int s = secs % 60;
                timerLabel.setText(String.format("⚠️ Overtime: +%02d:%02d", m, s));
                timerLabel.setTextFill(Color.web("#ef4444"));
            }
        };

        updateTimerDisplay.run();

        Runnable performSubmit = () -> {
            if (isSubmittedHolder[0]) return;
            isSubmittedHolder[0] = true;
            timerTimeline.stop();

            int score = 0;
            int total = questions.size();

            for (int i = 0; i < total; i++) {
                Map<String, Object> q = questions.get(i);
                String correctOpt = (String) q.get("correctOption");
                String studentAns = studentAnswers.get(i);

                if (correctOpt != null && correctOpt.equalsIgnoreCase(studentAns)) {
                    score++;
                }
            }

            int finalOvertime = overtimeSecondsHolder[0];

            try {
                resultDAO.saveResult(testId, user.getId(), score, total, finalOvertime);
            } catch (SQLException ex) {
                // Log exception
            }

            showResultScreen(stage, user, testTitle, score, total, finalOvertime, questions, studentAnswers);
        };

        KeyFrame kf = new KeyFrame(Duration.seconds(1), event -> {
            if (!isOvertimeActive[0]) {
                remainingSecondsHolder[0]--;
                if (remainingSecondsHolder[0] <= 0) {
                    if ("AUTO_SUBMIT".equalsIgnoreCase(expiryAction)) {
                        performSubmit.run();
                        return;
                    } else {
                        isOvertimeActive[0] = true;
                        overtimeSecondsHolder[0] = 0;
                    }
                }
            } else {
                overtimeSecondsHolder[0]++;
            }
            updateTimerDisplay.run();
        });

        timerTimeline.getKeyFrames().add(kf);
        timerTimeline.play();

        renderTestQuestionPage(stage, user, testTitle, questions, studentAnswers, 0, 
                               timerLabel, timerTimeline, performSubmit, textOrientation);
    }

    /**
     * Renders a single Question Page in the Test-Taking Screen with a Snug, Content-Fitting Centered Card layout.
     */
    private void renderTestQuestionPage(Stage stage, User user, String testTitle, 
                                        List<Map<String, Object>> questions, 
                                        Map<Integer, String> studentAnswers, 
                                        int index, Label timerLabel, 
                                        Timeline timerTimeline, 
                                        Runnable performSubmit, 
                                        NodeOrientation textOrientation) {
        VBox card = new VBox(16);
        card.setMaxWidth(640);
        card.setMaxHeight(Region.USE_PREF_SIZE); // Fit card height snugly to its content
        card.setPadding(new Insets(24, 28, 24, 28));
        card.setAlignment(Pos.CENTER);
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.06), 16, 0, 0, 4);");

        Map<String, Object> q = questions.get(index);
        int totalQuestions = questions.size();

        // 1. "Question X of Y" and "Time Remaining" at top
        Label progressLabel = new Label("Question " + (index + 1) + " of " + totalQuestions);
        progressLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
        progressLabel.setTextFill(Color.web("#10b981"));

        HBox topHeaderBox = new HBox(25, progressLabel, timerLabel);
        topHeaderBox.setAlignment(Pos.CENTER);

        // 2. Question Text
        Label qTextLabel = new Label((String) q.get("questionText"));
        qTextLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        qTextLabel.setTextFill(Color.web("#1e293b"));
        qTextLabel.setWrapText(true);
        qTextLabel.setNodeOrientation(textOrientation);

        // 3. Options Box
        ToggleGroup optionsGroup = new ToggleGroup();

        RadioButton rbA = new RadioButton("A. " + q.get("optionA"));
        RadioButton rbB = new RadioButton("B. " + q.get("optionB"));
        RadioButton rbC = new RadioButton("C. " + q.get("optionC"));
        RadioButton rbD = new RadioButton("D. " + q.get("optionD"));

        RadioButton[] rbs = new RadioButton[]{ rbA, rbB, rbC, rbD };
        String[] keys = new String[]{ "A", "B", "C", "D" };

        String previousAnswer = studentAnswers.get(index);

        for (int i = 0; i < 4; i++) {
            RadioButton rb = rbs[i];
            rb.setToggleGroup(optionsGroup);
            rb.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 14));
            rb.setTextFill(Color.web("#334155"));
            rb.setNodeOrientation(textOrientation);
            rb.setWrapText(true);

            if (keys[i].equalsIgnoreCase(previousAnswer)) {
                rb.setSelected(true);
            }

            final String optKey = keys[i];
            rb.setOnAction(e -> studentAnswers.put(index, optKey));
        }

        VBox optionsBox = new VBox(12, rbA, rbB, rbC, rbD);
        optionsBox.setAlignment(Pos.CENTER_LEFT);
        optionsBox.setPadding(new Insets(16));
        optionsBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        // 4. Buttons at bottom
        Button prevBtn = new Button("← Previous");
        prevBtn.setPrefWidth(140);
        prevBtn.setPrefHeight(44);
        prevBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        prevBtn.setDisable(index == 0);

        Button nextBtn = new Button("Next →");
        nextBtn.setPrefWidth(140);
        nextBtn.setPrefHeight(44);
        nextBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        nextBtn.setDisable(index == totalQuestions - 1);

        Button submitBtn = new Button("✓ Submit Test");
        submitBtn.setPrefWidth(170);
        submitBtn.setPrefHeight(44);
        submitBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        prevBtn.setOnAction(e -> renderTestQuestionPage(stage, user, testTitle, questions, studentAnswers, index - 1, timerLabel, timerTimeline, performSubmit, textOrientation));
        nextBtn.setOnAction(e -> renderTestQuestionPage(stage, user, testTitle, questions, studentAnswers, index + 1, timerLabel, timerTimeline, performSubmit, textOrientation));
        submitBtn.setOnAction(e -> performSubmit.run());

        HBox navBox = new HBox(12, prevBtn, nextBtn, submitBtn);
        navBox.setAlignment(Pos.CENTER);

        card.getChildren().addAll(topHeaderBox, qTextLabel, optionsBox, navBox);

        StackPane centerContainer = new StackPane(card);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 800, 650);
    }

    /**
     * RESULT SCREEN WITH DETAILED ANSWER REVIEW
     */
    private void showResultScreen(Stage stage, User user, String testTitle, int score, int total, 
                                  int overtimeSeconds, List<Map<String, Object>> questions, 
                                  Map<Integer, String> studentAnswers) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(620);
        rootBox.setAlignment(Pos.CENTER);

        Label header = new Label("Test Results");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));
        header.setTextFill(Color.web("#1e293b"));

        Label tTitle = new Label("Test: " + testTitle);
        tTitle.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 15));
        tTitle.setTextFill(Color.web("#64748b"));

        Label scoreDisplay = new Label(String.format("You scored %d out of %d", score, total));
        scoreDisplay.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        scoreDisplay.setTextFill(Color.web("#10b981"));

        double percentage = total > 0 ? ((double) score / total) * 100 : 0;
        Label percLabel = new Label(String.format("Score Percentage: %.1f%%", percentage));
        percLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 16));
        percLabel.setTextFill(Color.web("#334155"));

        VBox scoreCard = new VBox(8, scoreDisplay, percLabel);
        scoreCard.setAlignment(Pos.CENTER);
        scoreCard.setPadding(new Insets(20));
        scoreCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 10; -fx-background-radius: 10;");

        if (overtimeSeconds > 0) {
            Label otLabel = new Label("Overtime Used: " + overtimeSeconds + " seconds");
            otLabel.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 13));
            otLabel.setTextFill(Color.web("#ef4444"));
            scoreCard.getChildren().add(otLabel);
        }

        VBox reviewSectionBox = new VBox(14);
        reviewSectionBox.setAlignment(Pos.TOP_LEFT);

        Label reviewTitle = new Label("Detailed Answer Review:");
        reviewTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        reviewTitle.setTextFill(Color.web("#1e293b"));
        reviewSectionBox.getChildren().add(reviewTitle);

        for (int i = 0; i < questions.size(); i++) {
            Map<String, Object> q = questions.get(i);
            String correctKey = (String) q.get("correctOption");
            String studentKey = studentAnswers.get(i);

            boolean isCorrect = (correctKey != null && correctKey.equalsIgnoreCase(studentKey));

            VBox qReviewCard = new VBox(8);
            qReviewCard.setPadding(new Insets(14, 16, 14, 16));

            if (isCorrect) {
                qReviewCard.setStyle("-fx-background-color: #f0fdf4; -fx-border-color: #bbf7d0; -fx-border-radius: 8; -fx-background-radius: 8;");
            } else {
                qReviewCard.setStyle("-fx-background-color: #fef2f2; -fx-border-color: #fecaca; -fx-border-radius: 8; -fx-background-radius: 8;");
            }

            Label badge = new Label(isCorrect ? "✔ Correct" : "✘ Incorrect");
            badge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
            badge.setTextFill(Color.web(isCorrect ? "#16a34a" : "#dc2626"));

            Label qText = new Label(String.format("Q%d. %s", (i + 1), q.get("questionText")));
            qText.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 15));
            qText.setTextFill(Color.web("#1e293b"));
            qText.setWrapText(true);

            HBox cardHeader = new HBox(12, badge, qText);
            cardHeader.setAlignment(Pos.CENTER_LEFT);

            String studentAnsText = formatOptionText(q, studentKey);
            Label yourAnsLabel = new Label("Your Answer: " + (studentKey != null ? studentKey + ". " + studentAnsText : "Not answered"));
            yourAnsLabel.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 13));
            yourAnsLabel.setTextFill(Color.web(isCorrect ? "#16a34a" : "#dc2626"));

            qReviewCard.getChildren().addAll(cardHeader, yourAnsLabel);

            if (!isCorrect) {
                String correctAnsText = formatOptionText(q, correctKey);
                Label correctAnsLabel = new Label("Correct Answer: " + correctKey + ". " + correctAnsText);
                correctAnsLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
                correctAnsLabel.setTextFill(Color.web("#16a34a"));
                qReviewCard.getChildren().add(correctAnsLabel);
            }

            reviewSectionBox.getChildren().add(qReviewCard);
        }

        Button returnDashBtn = new Button("Return to Student Dashboard");
        returnDashBtn.setMaxWidth(Double.MAX_VALUE);
        returnDashBtn.setPrefHeight(44);
        returnDashBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        returnDashBtn.setOnAction(e -> showStudentDashboard(stage, user));

        rootBox.getChildren().addAll(header, tTitle, scoreCard, reviewSectionBox, returnDashBtn);

        StackPane centerContainer = new StackPane(rootBox);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane rootContainer = createCenteredRoot(scrollPane);
        setScreenRoot(stage, rootContainer, 800, 700);
    }

    private String formatOptionText(Map<String, Object> questionMap, String key) {
        if (key == null || questionMap == null) return "None";
        Object val;
        switch (key.toUpperCase()) {
            case "A": val = questionMap.get("optionA"); break;
            case "B": val = questionMap.get("optionB"); break;
            case "C": val = questionMap.get("optionC"); break;
            case "D": val = questionMap.get("optionD"); break;
            default: return "None";
        }
        return val != null ? val.toString() : "None";
    }

    /**
     * Helper to safely extract an int from an Object (e.g. Integer/Long from database map).
     */
    private int toInt(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        if (obj != null) {
            try {
                return Integer.parseInt(obj.toString());
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private void showMessage(Label label, String message, boolean isSuccess) {
        label.setText(message);
        label.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
        if (isSuccess) {
            label.setTextFill(Color.web("#10b981"));
        } else {
            label.setTextFill(Color.web("#ef4444"));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}