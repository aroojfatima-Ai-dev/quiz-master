package com.quiz;

import com.quiz.dao.ClassDAO;
import com.quiz.dao.ResultDAO;
import com.quiz.dao.TestDAO;
import com.quiz.dao.UserDAO;
import com.quiz.importing.ImportResult;
import com.quiz.importing.ParseFailure;
import com.quiz.importing.ParsedQuestion;
import com.quiz.importing.QuestionFileParser;
import com.quiz.importing.QuestionFileReader;
import com.quiz.model.Question;
import com.quiz.model.User;
import com.quiz.validation.InputValidator;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
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
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Main JavaFX Application class for QuizMaster.
 * Features Water Bubble Animated Background on every screen, Multi-Screen Hub Architecture,
 * Salted PBKDF2 Password Hashing, Class/Code Isolation System, RTL Urdu Support, Timer,
 * and Answer Review.
 *
 * <p>All screens are built in code; there are no FXML files. Validation rules live in
 * {@link InputValidator} and password handling in {@code com.quiz.security.PasswordUtil}
 * so both can be unit tested without launching the UI.
 */
public class App extends Application {

    private final UserDAO userDAO = new UserDAO();
    private final TestDAO testDAO = new TestDAO();
    private final ResultDAO resultDAO = new ResultDAO();
    private final ClassDAO classDAO = new ClassDAO();

    /**
     * Animations created for the root that {@link #createCenteredRoot} built most
     * recently but that has not been installed into the scene yet.
     */
    private List<Animation> pendingAnimations = new ArrayList<>();

    /**
     * Countdown of the test being taken, if any.
     *
     * <p>The quiz timer deliberately survives page navigation (it must not restart when a
     * student moves between questions), so it is not part of {@link #liveAnimations}. That
     * means it has to be stopped explicitly whenever it stops being relevant - see
     * {@link #stopQuizTimer()}.
     */
    private Timeline quizTimerTimeline;

    /**
     * Animations belonging to the root currently displayed. They are stopped when
     * that root is replaced - see {@link #setScreenRoot}.
     */
    private List<Animation> liveAnimations = new ArrayList<>();

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
        installGlobalExceptionHandler();

        stage.setTitle("QuizMaster Application");
        showWelcomeScreen(stage);
        stage.show();
    }

    /**
     * Last line of defence so an unforeseen problem shows a message instead of
     * silently killing a screen or the window.
     *
     * <p>Everything that is expected to fail - a corrupt PDF, an unreadable file, a
     * rejected question - is already handled where it happens and reported in the
     * screen that caused it. This only catches what nobody anticipated.
     */
    private void installGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("Unhandled exception on thread '" + thread.getName() + "':");
            throwable.printStackTrace();

            // Reporting must not itself throw, and must not run before the toolkit is
            // up, so it is done defensively on the FX thread.
            try {
                Platform.runLater(() -> {
                    try {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("QuizMaster - Unexpected Error");
                        alert.setHeaderText("Something went wrong, but QuizMaster is still running.");
                        String detail = throwable.getMessage();
                        alert.setContentText(detail == null || detail.isBlank()
                                ? throwable.getClass().getSimpleName()
                                : detail);
                        alert.showAndWait();
                    } catch (RuntimeException ignored) {
                        // The alert is a courtesy; the stack trace above is the record.
                    }
                });
            } catch (RuntimeException ignored) {
                // Toolkit not available - the stack trace above is enough.
            }
        });
    }

    /**
     * Seamless screen transition helper: swaps the root container of the active Scene.
     * Prevents the stage/window from shrinking or un-maximizing during navigation.
     *
     * <p>This is also where the outgoing screen's animations are torn down. Bubble
     * animations run with an INDEFINITE cycle count, and a playing JavaFX animation is
     * strongly referenced by the toolkit's animation timer, so it is never garbage
     * collected on its own. Without this cleanup every navigation leaked the whole
     * bubble field of the previous screen - a test with 20 questions leaked 20 sets.
     *
     * <p>Only the animations belonging to the replaced root are stopped; the quiz
     * timer is tracked separately and keeps running across question pages.
     */
    private void setScreenRoot(Stage stage, Parent rootContainer, double defaultWidth, double defaultHeight) {
        stopAnimations(liveAnimations);
        liveAnimations = pendingAnimations;
        pendingAnimations = new ArrayList<>();

        if (stage.getScene() == null) {
            Scene scene = new Scene(rootContainer, defaultWidth, defaultHeight);
            stage.setScene(scene);
        } else {
            stage.getScene().setRoot(rootContainer);
        }
    }

    /**
     * Called by JavaFX when the application exits. Stops whatever is still animating
     * so the toolkit does not keep the pulse timer alive.
     */
    @Override
    public void stop() {
        stopQuizTimer();
        stopAnimations(liveAnimations);
        stopAnimations(pendingAnimations);
        liveAnimations = new ArrayList<>();
        pendingAnimations = new ArrayList<>();
    }

    /**
     * Stops the countdown of a test that is no longer being taken.
     *
     * <p>An {@code INDEFINITE} JavaFX animation keeps firing until it is stopped, so a
     * countdown left behind by a test would keep ticking once a second for the rest of the
     * session and keep the stage alive after the window was closed.
     */
    private void stopQuizTimer() {
        if (quizTimerTimeline != null) {
            quizTimerTimeline.stop();
            quizTimerTimeline = null;
        }
    }

    /** Stops every animation in the list and empties it. */
    private static void stopAnimations(List<Animation> animations) {
        for (Animation animation : animations) {
            animation.stop();
        }
        animations.clear();
    }

    /**
     * Helper method to create a full-window container with animated water bubbles floating outside the center card.
     *
     * <p>All bubbles are driven by a single {@link Timeline}. The previous version
     * created one Timeline per bubble, i.e. 42 timers each firing every 30ms
     * (~1,400 animation callbacks per second) for every screen that was shown.
     */
    private StackPane createCenteredRoot(Node content) {
        // Animations for this root; handed over to liveAnimations by setScreenRoot.
        List<Animation> animations = new ArrayList<>();
        pendingAnimations = animations;

        StackPane root = new StackPane();
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #f0f9ff;"); // Light sky blue canvas

        // Animated Water Bubble Layer
        Pane bubbleLayer = new Pane();
        bubbleLayer.setMouseTransparent(true);

        Random random = new Random();
        int bubbleCount = 42;

        // Per-bubble motion state, indexed in parallel with the bubbles list so the
        // shared Timeline below can update every bubble in one callback.
        List<Circle> bubbles = new ArrayList<>(bubbleCount);
        double[] anchorY = new double[bubbleCount];
        double[] speedPixels = new double[bubbleCount];
        double[] wobbleSpeed = new double[bubbleCount];
        double[] waveAngle = new double[bubbleCount];

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

            // Bindings need values that stay effectively final inside the loop.
            final double bindX = relX;
            final double bindY = relY;
            bubble.layoutXProperty().bind(root.widthProperty().multiply(bindX));
            bubble.layoutYProperty().bind(root.heightProperty().multiply(bindY));

            anchorY[i] = relY;
            speedPixels[i] = 0.4 + random.nextDouble() * 0.8;
            wobbleSpeed[i] = 0.02 + random.nextDouble() * 0.04;
            waveAngle[i] = random.nextDouble() * Math.PI * 2;

            // Soft pulse / twinkle opacity
            FadeTransition fade = new FadeTransition(Duration.seconds(1.4 + random.nextDouble() * 2.2), bubble);
            fade.setFromValue(0.12 + random.nextDouble() * 0.15);
            fade.setToValue(0.45 + random.nextDouble() * 0.25);
            fade.setCycleCount(FadeTransition.INDEFINITE);
            fade.setAutoReverse(true);
            fade.play();
            animations.add(fade);

            bubbles.add(bubble);
            bubbleLayer.getChildren().add(bubble);
        }

        // Continuous 3D-like upward drift with gentle sine wave wobble, for all bubbles.
        Timeline drift = new Timeline(new KeyFrame(Duration.millis(30), e -> {
            double h = root.getHeight() > 0 ? root.getHeight() : 650;
            for (int i = 0; i < bubbles.size(); i++) {
                Circle bubble = bubbles.get(i);
                double initialYRatio = anchorY[i];
                double currentY = bubble.getTranslateY();
                double nextY = currentY - speedPixels[i];

                if (nextY < -(h * initialYRatio + 40)) {
                    nextY = h * (1.0 - initialYRatio) + 40;
                }
                bubble.setTranslateY(nextY);

                waveAngle[i] += wobbleSpeed[i];
                bubble.setTranslateX(Math.sin(waveAngle[i]) * 14);
            }
        }));
        drift.setCycleCount(Timeline.INDEFINITE);
        drift.play();
        animations.add(drift);

        root.getChildren().addAll(bubbleLayer, content);
        return root;
    }

    /**
     * Wraps a screen's content in a centred, scrollable container, adds the animated
     * background and installs it as the current screen.
     *
     * <p>This replaced ten copies of the same inline block. {@code fitToHeight} must
     * stay {@code false}: when it is {@code true} JavaFX resizes the content to fill
     * the viewport height instead of letting it overflow, so a tall form was squeezed
     * into the window, its fields were clipped, and no vertical scrollbar ever
     * appeared. {@code fitToWidth} stays {@code true} so the card still spans the
     * available width.
     */
    private void installScrollableScreen(Stage stage, Node content,
                                         double defaultWidth, double defaultHeight) {
        StackPane centerContainer = new StackPane(content);
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(20));

        ScrollPane scrollPane = new ScrollPane(centerContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        setScreenRoot(stage, createCenteredRoot(scrollPane), defaultWidth, defaultHeight);
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

            String validationError = InputValidator.validateRegistration(username, email, password, confirmPassword);
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
     * SEPARATE SCREEN: TEACHER MY CLASSES SCREEN
     */
    private void showTeacherClassesScreen(Stage stage, User user) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(580);
        rootBox.setAlignment(Pos.CENTER);

        // Card 1: Create Class Form (Teacher enters Class Name AND Chosen 6-Char Class Code)
        VBox createCard = new VBox(14);
        createCard.setPadding(new Insets(22));
        createCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12;");

        Label title1 = new Label("➕ Create a New Class");
        title1.setFont(Font.font("Segoe UI", FontWeight.BOLD, 19));
        title1.setTextFill(Color.web("#8e44ad"));

        TextField classNameField = new TextField();
        classNameField.setPromptText("Class Name (e.g. Physics 101)");
        classNameField.setStyle(INPUT_STYLE);
        classNameField.setPrefHeight(44);

        TextField classCodeField = new TextField();
        classCodeField.setPromptText("Chosen Class Code (6 letters/numbers, e.g. PHY101)");
        classCodeField.setStyle(INPUT_STYLE);
        classCodeField.setPrefHeight(44);

        Label createMsg = new Label();
        createMsg.setWrapText(true);

        Button createBtn = new Button("Create Class");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setPrefHeight(44);
        createBtn.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; -fx-background-radius: 6; -fx-cursor: hand;");

        createCard.getChildren().addAll(
                title1, 
                new Label("Class Name:"), classNameField, 
                new Label("Class Code (6 alphanumeric characters):"), classCodeField, 
                createBtn, createMsg
        );

        // Card 2: List of Existing Classes
        VBox listCard = new VBox(12);
        listCard.setPadding(new Insets(22));
        listCard.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12;");

        Label title2 = new Label("🏫 My Existing Classes:");
        title2.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        title2.setTextFill(Color.web("#1e293b"));

        VBox classesListView = new VBox(10);

        Runnable refreshList = () -> {
            classesListView.getChildren().clear();
            try {
                List<Map<String, Object>> classes = classDAO.getClassesByTeacher(user.getId());
                if (classes.isEmpty()) {
                    Label empty = new Label("You haven't created any classes yet.");
                    empty.setTextFill(Color.web("#94a3b8"));
                    classesListView.getChildren().add(empty);
                } else {
                    for (Map<String, Object> c : classes) {
                        HBox cItem = new HBox(12);
                        cItem.setPadding(new Insets(12));
                        cItem.setAlignment(Pos.CENTER_LEFT);
                        cItem.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6;");

                        VBox info = new VBox(4);
                        Label cName = new Label((String) c.get("className"));
                        cName.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
                        cName.setTextFill(Color.web("#1e293b"));

                        Label cCode = new Label("Class Code: " + c.get("classCode") + " | Enrolled: " + c.get("studentCount") + " students");
                        cCode.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 13));
                        cCode.setTextFill(Color.web("#0284c7"));

                        info.getChildren().addAll(cName, cCode);
                        HBox.setHgrow(info, Priority.ALWAYS);

                        cItem.getChildren().add(info);
                        classesListView.getChildren().add(cItem);
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
            // Locale.ROOT upper-casing: on a Turkish system toUpperCase() turned 'i'
            // into a dotted capital 'I', which then failed the alphanumeric check.
            String code = InputValidator.normalizeClassCode(classCodeField.getText());

            String validationError = InputValidator.validateClass(name, code);
            if (validationError != null) {
                showMessage(createMsg, validationError, false);
                return;
            }

            try {
                classDAO.createClass(user.getId(), name, code);
                showMessage(createMsg, "Class '" + name + "' created successfully with code: " + code, true);
                classNameField.clear();
                classCodeField.clear();
                refreshList.run();

            } catch (ClassDAO.DuplicateClassCodeException ex) {
                showMessage(createMsg, ex.getMessage(), false);
            } catch (SQLException ex) {
                showMessage(createMsg, "Database Error: " + ex.getMessage(), false);
            }
        });

        listCard.getChildren().addAll(title2, classesListView);

        Button backBtn = new Button("← Back to Dashboard");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showTeacherDashboard(stage, user, null));

        rootBox.getChildren().addAll(createCard, listCard, backBtn);

        installScrollableScreen(stage, rootBox, 750, 680);
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

                    Label detailsLabel = new Label(String.format(Locale.ROOT, "Language: %s | Duration: %s | Created: %s",
                            t.get("language"), InputValidator.formatDuration(toInt(t.get("totalTimeSeconds"))), t.get("createdAt")));
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

        installScrollableScreen(stage, rootBox, 750, 680);
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

                    Label scoreLabel = new Label(String.format(Locale.ROOT, "Score: %d / %d (%.1f%%) | Date: %s", score, total, perc, sub.get("takenAt")));
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

        installScrollableScreen(stage, rootBox, 750, 680);
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

        // Visibility Options: Public vs Assign to Class
        Label visLabel = new Label("Test Visibility:");
        visLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));

        RadioButton publicRadio = new RadioButton("🌐 Make Public (all students)");
        RadioButton classRadio = new RadioButton("🔒 Assign to a Class");
        ToggleGroup visGroup = new ToggleGroup();
        publicRadio.setToggleGroup(visGroup);
        classRadio.setToggleGroup(visGroup);

        if (draftTest.isPublic) {
            publicRadio.setSelected(true);
        } else {
            classRadio.setSelected(true);
        }

        HBox visBox = new HBox(12, publicRadio, classRadio);
        visBox.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> classCombo = new ComboBox<>();
        classCombo.setPromptText("Select a Class...");
        classCombo.setMaxWidth(Double.MAX_VALUE);
        classCombo.setStyle(COMBO_STYLE);
        classCombo.setPrefHeight(42);
        classCombo.setDisable(draftTest.isPublic);

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);

        Map<String, Integer> classMap = new HashMap<>();
        // Single-element array so the flag stays effectively final for the lambda below.
        boolean[] classesLoaded = new boolean[]{ true };
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
            // This used to be an empty "// Ignore error" block. The combo stayed empty
            // and a teacher who picked "Assign to a Class" was only told to "select a
            // valid class", with no hint that the class list had failed to load.
            classesLoaded[0] = false;
            showMessage(messageLabel, "Could not load your classes: " + ex.getMessage(), false);
        }

        publicRadio.setOnAction(e -> classCombo.setDisable(true));
        classRadio.setOnAction(e -> classCombo.setDisable(false));

        VBox visContainer = new VBox(8, visLabel, visBox, classCombo);
        visContainer.setStyle("-fx-background-color: #f8fafc; -fx-padding: 10 14; -fx-background-radius: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 6;");

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

            String titleError = InputValidator.validateTestTitle(title);
            if (titleError != null) {
                showMessage(messageLabel, titleError, false);
                return;
            }
            if (timeStr.isEmpty() || mcqStr.isEmpty()) {
                showMessage(messageLabel, "Please fill in all required fields.", false);
                return;
            }

            // parsePositiveInt returns -1 for anything that is not a positive int,
            // including values that would overflow Integer.parseInt.
            int minutes = InputValidator.parsePositiveInt(timeStr);
            if (minutes < 0) {
                showMessage(messageLabel, "Please enter a valid positive number for minutes.", false);
                return;
            }
            // Without this cap, minutes * 60 overflows int and the test is stored with
            // a negative duration, which expires the instant a student opens it.
            if (minutes > InputValidator.MAX_TEST_MINUTES) {
                showMessage(messageLabel, "Duration must be at most "
                        + InputValidator.MAX_TEST_MINUTES + " minutes.", false);
                return;
            }

            int mcqCount = InputValidator.parsePositiveInt(mcqStr);
            if (mcqCount < 0) {
                showMessage(messageLabel, "Please enter a valid positive number for MCQs count.", false);
                return;
            }

            boolean isPub = publicRadio.isSelected();
            Integer selectedClassId = null;

            if (!isPub) {
                String selectedDisplay = classCombo.getValue();
                if (selectedDisplay == null || !classMap.containsKey(selectedDisplay)) {
                    showMessage(messageLabel, classesLoaded[0]
                            ? "Please select a valid class to assign this private test."
                            : "Your class list could not be loaded, so this test cannot be assigned to a class yet.",
                            false);
                    return;
                }
                selectedClassId = classMap.get(selectedDisplay);
            }

            draftTest.title = title;
            draftTest.language = lang;
            draftTest.timeMinutes = minutes;
            draftTest.targetQuestionCount = mcqCount;
            // Null-safe: an unset combo value falls back to AUTO_SUBMIT, which is the
            // stricter default, rather than silently allowing unlimited overtime.
            draftTest.expiryAction = "Allow overtime".equals(expiryStr) ? "ALLOW_OVERTIME" : "AUTO_SUBMIT";
            draftTest.isPublic = isPub;
            draftTest.classId = selectedClassId;

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
                messageLabel,
                btnBox
        );

        installScrollableScreen(stage, form, 750, 700);
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

        // ------------------------------------------------------------------
        // Alternative to typing questions one by one: import them from a file.
        // Deliberately placed after the manual controls so the manual flow is
        // unchanged; this is purely an additional route to the same draft list.
        // ------------------------------------------------------------------
        Label uploadHeading = new Label("Or import questions from a file");
        uploadHeading.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        uploadHeading.setTextFill(Color.web("#0f172a"));

        Label uploadHint = new Label("Upload a PDF, Word (.docx) or text (.txt) file that follows "
                + "the required format. You will see a preview before anything is saved.");
        uploadHint.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
        uploadHint.setTextFill(Color.web("#475569"));
        uploadHint.setWrapText(true);

        Label uploadFormatPeek = new Label("Q: <question text>   |   A) ...  B) ...  C) ...  D) ...   |   "
                + "Answer: A   |   Topic: ...   |   Difficulty: EASY");
        uploadFormatPeek.setFont(Font.font("Consolas", FontWeight.NORMAL, 11));
        uploadFormatPeek.setTextFill(Color.web("#0f172a"));
        uploadFormatPeek.setWrapText(true);
        uploadFormatPeek.setPadding(new Insets(8, 10, 8, 10));
        uploadFormatPeek.setMaxWidth(Double.MAX_VALUE);
        uploadFormatPeek.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; "
                + "-fx-border-radius: 6; -fx-background-radius: 6;");

        Button uploadBtn = new Button("📄  Upload Questions File");
        uploadBtn.setMaxWidth(Double.MAX_VALUE);
        uploadBtn.setPrefHeight(44);
        uploadBtn.setStyle("-fx-background-color: #7c3aed; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 15px; -fx-background-radius: 6; -fx-cursor: hand;");

        uploadBtn.setOnAction(e -> {
            // Keep whatever is currently on screen, exactly as the Back button does,
            // so navigating away to the upload screen cannot lose typed work.
            if (!questionTextField.getText().trim().isEmpty()) {
                saveCurrentQuestionToMemory.run();
            }
            showImportUploadScreen(stage, user, draftTest, draftQuestions);
        });

        VBox uploadBox = new VBox(8, uploadHeading, uploadHint, uploadFormatPeek, uploadBtn);
        uploadBox.setPadding(new Insets(14));
        uploadBox.setStyle("-fx-background-color: #faf5ff; -fx-border-color: #d8b4fe; "
                + "-fx-border-radius: 10; -fx-background-radius: 10;");

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

        // Set once the test has been written, so a double click cannot create it twice.
        boolean[] alreadySaved = new boolean[]{ false };

        nextBtn.setOnAction(e -> {
            String questionError = InputValidator.validateQuestion(
                    questionTextField.getText(), optAField.getText(), optBField.getText(),
                    optCField.getText(), optDField.getText());
            if (questionError != null) {
                showMessage(messageLabel, questionError, false);
                return;
            }
            // questions.topic is VARCHAR(100). Without this check a longer topic reached
            // MySQL and came back as an unhelpful "Data too long for column 'topic'".
            String topicError = InputValidator.validateTopic(topicField.getText());
            if (topicError != null) {
                showMessage(messageLabel, topicError, false);
                return;
            }

            saveCurrentQuestionToMemory.run();
            showWizardStep2QuestionScreen(stage, user, draftTest, draftQuestions, questionIndex + 1);
        });

        finishBtn.setOnAction(e -> {
            if (alreadySaved[0]) {
                return;
            }
            String topicError = InputValidator.validateTopic(topicField.getText());
            if (topicError != null) {
                showMessage(messageLabel, topicError, false);
                return;
            }

            // Save the question currently on screen, but only when it is complete.
            // This check used to look at options A and B alone, so "Finish" could
            // persist a question with blank options C and D - students then saw two
            // empty radio buttons for it.
            if (InputValidator.validateQuestion(
                    questionTextField.getText(), optAField.getText(), optBField.getText(),
                    optCField.getText(), optDField.getText()) == null) {
                saveCurrentQuestionToMemory.run();
            }

            if (draftQuestions.isEmpty()) {
                showMessage(messageLabel, "Please add at least 1 valid question before finishing.", false);
                return;
            }

            // Re-check the whole draft list, not just the question on screen. The Back
            // button and the upload button both save the current question without
            // validating it, so a half-typed one can sit in the list; "Finish" used to
            // persist it with blank options, and students then saw empty radio buttons.
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < draftQuestions.size(); i++) {
                DraftQuestion q = draftQuestions.get(i);
                String error = InputValidator.validateQuestion(q.questionText, q.optionA,
                        q.optionB, q.optionC, q.optionD);
                if (error == null) {
                    error = InputValidator.validateTopic(q.topic);
                }
                if (error != null) {
                    problems.add("Q" + (i + 1) + ": " + error);
                }
            }
            if (!problems.isEmpty()) {
                showMessage(messageLabel, "These questions are incomplete — "
                        + String.join("  |  ", problems), false);
                return;
            }

            try {
                int totalTimeSeconds = draftTest.timeMinutes * 60;

                // Write the test and all of its questions on one connection in one
                // transaction. Previously this was 1 + N auto-commit calls on N + 1
                // separate connections, so a failure part-way through left a test
                // permanently holding only some of its questions.
                List<Question> questions = new ArrayList<>(draftQuestions.size());
                for (DraftQuestion q : draftQuestions) {
                    // A blank topic falls back to "General" rather than reaching MySQL as
                    // an empty string (the column is NOT NULL with that default).
                    String safeTopic = q.topic == null || q.topic.trim().isEmpty()
                            ? "General" : q.topic.trim();
                    String safeDifficulty = q.difficulty == null || q.difficulty.trim().isEmpty()
                            ? "MEDIUM" : q.difficulty.trim();
                    questions.add(new Question(q.questionText, q.optionA, q.optionB, q.optionC,
                            q.optionD, q.correctOption, safeTopic, safeDifficulty));
                }

                testDAO.createTestWithQuestions(
                        draftTest.title, draftTest.language, totalTimeSeconds,
                        draftTest.expiryAction, user.getId(),
                        draftTest.classId, draftTest.isPublic, questions
                );
                alreadySaved[0] = true;

                String successMsg = String.format(Locale.ROOT, "Test '%s' created successfully with %d questions!",
                        draftTest.title, draftQuestions.size());
                showTeacherDashboard(stage, user, successMsg);

            } catch (SQLException ex) {
                showMessage(messageLabel, "Failed to save test to database: " + ex.getMessage(), false);
            } catch (IllegalArgumentException ex) {
                // The DAO re-checks every question before writing; reaching this means a
                // draft slipped past the screen checks above.
                showMessage(messageLabel, ex.getMessage(), false);
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
                navBox,
                uploadBox
        );

        installScrollableScreen(stage, form, 750, 700);
    }

    // =========================================================================
    // IMPORT QUESTIONS FROM A FILE
    //
    // An additional route to the same draft list the wizard builds by hand. The
    // parse is done by com.quiz.importing (PDFBox / POI / plain text) and nothing
    // reaches the database until "Confirm & Save All" is pressed, which writes the
    // test and every question on one connection in one transaction.
    // =========================================================================

    /**
     * UPLOAD SCREEN: shows the required file format, then lets the teacher pick a
     * file. Parsing happens here, and any problem with the file is reported on this
     * screen without leaving it.
     */
    private void showImportUploadScreen(Stage stage, User user,
                                        DraftTestDetails draftTest,
                                        List<DraftQuestion> draftQuestions) {
        VBox card = new VBox(16);
        card.setMaxWidth(620);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(26));
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; "
                + "-fx-border-radius: 12; -fx-background-radius: 12; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 16, 0, 0, 4);");

        Label header = new Label("Upload Questions File");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        header.setTextFill(Color.web("#7c3aed"));

        Label sub = new Label("Test: " + draftTest.title + " (" + draftTest.language + ")");
        sub.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
        sub.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(4, header, sub);
        headerBox.setAlignment(Pos.CENTER);

        // ---- The format, exactly as teachers must write it ----
        Label formatTitle = new Label("Required file format");
        formatTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        formatTitle.setTextFill(Color.web("#0f172a"));

        Label formatExample = new Label(QuestionFileParser.DOCUMENTED_FORMAT);
        formatExample.setFont(Font.font("Consolas", FontWeight.NORMAL, 12));
        formatExample.setTextFill(Color.web("#0f172a"));
        formatExample.setPadding(new Insets(12, 14, 12, 14));
        formatExample.setMaxWidth(Double.MAX_VALUE);
        formatExample.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; "
                + "-fx-border-radius: 6; -fx-background-radius: 6;");

        Label notesTitle = new Label("Rules");
        notesTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        notesTitle.setTextFill(Color.web("#0f172a"));

        Label notes = new Label(
                "• Put a blank line between questions (not required, but easier to read).\n"
              + "• Supported files: .pdf, .docx and .txt.\n"
              + "• Topic and Difficulty are optional — they default to General and MEDIUM.\n"
              + "• Questions are imported in the order they appear in the file.\n"
              + "• Any question that does not match the format is skipped and listed with the reason.\n"
              + "• Nothing is saved until you press \"Confirm & Save All\" on the preview screen.");
        notes.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
        notes.setTextFill(Color.web("#475569"));
        notes.setWrapText(true);
        notes.setMaxWidth(Double.MAX_VALUE);

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);

        Button chooseBtn = new Button("📂  Choose File…");
        chooseBtn.setMaxWidth(Double.MAX_VALUE);
        chooseBtn.setPrefHeight(46);
        chooseBtn.setStyle("-fx-background-color: #7c3aed; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 15px; -fx-background-radius: 6; -fx-cursor: hand;");

        Button backBtn = new Button("← Back to Questions");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showWizardStep2QuestionScreen(stage, user, draftTest,
                draftQuestions, draftQuestions.size()));

        chooseBtn.setOnAction(e -> {
            File chosen;
            try {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Select Questions File");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("Questions files (*.pdf, *.docx, *.txt)",
                                "*.pdf", "*.docx", "*.txt"),
                        new FileChooser.ExtensionFilter("PDF documents (*.pdf)", "*.pdf"),
                        new FileChooser.ExtensionFilter("Word documents (*.docx)", "*.docx"),
                        new FileChooser.ExtensionFilter("Text files (*.txt)", "*.txt"),
                        new FileChooser.ExtensionFilter("All files", "*.*"));
                chosen = chooser.showOpenDialog(stage);
            } catch (RuntimeException ex) {
                // A native file dialog can fail on a machine with no usable desktop;
                // that must not take the application down.
                showMessage(messageLabel, "The file chooser could not be opened: " + ex.getMessage(), false);
                return;
            }

            if (chosen == null) {
                return; // The teacher cancelled - not an error.
            }

            try {
                String text = QuestionFileReader.readText(chosen);
                ImportResult result = QuestionFileParser.parse(text);

                if (result.getQuestions().isEmpty()) {
                    showMessage(messageLabel, result.getFailures().isEmpty()
                            ? "No questions were found in '" + chosen.getName() + "'."
                            : "No questions could be read from '" + chosen.getName()
                              + "'. See the reasons below the format example.", false);
                    showImportFailureSummary(card, messageLabel, result.getFailures());
                    return;
                }

                // Working copy: the preview owns these until the teacher confirms, so
                // cancelling leaves the manual draft exactly as it was.
                List<DraftQuestion> imported = new ArrayList<>();
                for (ParsedQuestion q : result.getQuestions()) {
                    imported.add(toDraftQuestion(q));
                }

                showImportPreviewScreen(stage, user, draftTest, draftQuestions,
                        chosen.getName(), imported, result.getFailures(), new int[]{ 0 });
                return;

            } catch (IOException ex) {
                // QuestionFileReader's messages are written to be shown verbatim.
                showMessage(messageLabel, ex.getMessage(), false);
            } catch (RuntimeException ex) {
                // Belt and braces: the promise is that no file can crash the app.
                showMessage(messageLabel, "The file '" + chosen.getName()
                        + "' could not be processed: " + ex.getMessage(), false);
            }
        });

        VBox formatBox = new VBox(8, formatTitle, formatExample, notesTitle, notes);
        formatBox.setPadding(new Insets(14));
        formatBox.setStyle("-fx-background-color: #faf5ff; -fx-border-color: #d8b4fe; "
                + "-fx-border-radius: 10; -fx-background-radius: 10;");

        card.getChildren().addAll(headerBox, formatBox, messageLabel, chooseBtn, backBtn);

        installScrollableScreen(stage, card, 750, 700);
    }

    /**
     * Appends a short list of skipped-question reasons to the upload card, so a file
     * that yielded nothing still tells the teacher why. Any list added by an earlier
     * attempt is replaced rather than stacked.
     */
    private void showImportFailureSummary(VBox card, Label messageLabel, List<ParseFailure> failures) {
        card.getChildren().removeIf(node -> "importFailures".equals(node.getId()));
        if (failures.isEmpty()) {
            return;
        }

        VBox list = new VBox(6);
        list.setId("importFailures");
        list.setPadding(new Insets(12));
        list.setStyle("-fx-background-color: #fef2f2; -fx-border-color: #fecaca; "
                + "-fx-border-radius: 8; -fx-background-radius: 8;");

        for (ParseFailure failure : failures) {
            Label item = new Label(failureLabel(failure));
            item.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
            item.setTextFill(Color.web("#b91c1c"));
            item.setWrapText(true);
            item.setMaxWidth(Double.MAX_VALUE);
            list.getChildren().add(item);
        }

        int index = card.getChildren().indexOf(messageLabel);
        card.getChildren().add(index < 0 ? card.getChildren().size() : index + 1, list);
    }

    /** Renders one skipped entry as "Line 12: reason — "snippet"". */
    private static String failureLabel(ParseFailure failure) {
        StringBuilder text = new StringBuilder();
        text.append(failure.getLineNumber() > 0 ? "Line " + failure.getLineNumber() + ": " : "File: ")
            .append(failure.getReason());
        if (!failure.getSnippet().isEmpty()) {
            text.append("  —  ").append(failure.getSnippet());
        }
        return text.toString();
    }

    /**
     * PREVIEW SCREEN: every parsed question in file order, plus the ones that were
     * skipped. Nothing is saved yet; each row can be edited or removed, and
     * "Confirm & Save All" writes the whole test in a single transaction.
     *
     * <p>The working list of imported questions is passed in rather than rebuilt from
     * the parse result. Returning here used to re-derive it from
     * {@code result.getQuestions()}, which threw away every edit and every removal the
     * teacher had just made: editing a question and pressing "Save Changes" appeared to
     * work, and the original text came straight back.
     *
     * @param imported     the working copy this preview owns until the teacher confirms
     * @param failures     the blocks the parser skipped, to list under the questions
     * @param removedCount shared count of rows removed here, so the "everything was
     *                     removed" message survives a trip through the edit screen
     */
    private void showImportPreviewScreen(Stage stage, User user,
                                         DraftTestDetails draftTest,
                                         List<DraftQuestion> draftQuestions,
                                         String fileName,
                                         List<DraftQuestion> imported,
                                         List<ParseFailure> failures,
                                         final int[] removedCount) {

        VBox card = new VBox(14);
        card.setMaxWidth(680);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(24));
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; "
                + "-fx-border-radius: 12; -fx-background-radius: 12; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 16, 0, 0, 4);");

        Label header = new Label("Review Imported Questions");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        header.setTextFill(Color.web("#7c3aed"));

        Label fileLabel = new Label("From: " + fileName + "  |  " + imported.size()
                + (imported.size() == 1 ? " question found" : " questions found")
                + "  |  nothing saved yet");
        fileLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
        fileLabel.setTextFill(Color.web("#64748b"));
        fileLabel.setWrapText(true);

        VBox headerBox = new VBox(4, header, fileLabel);
        headerBox.setAlignment(Pos.CENTER);

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);

        // ---- Skipped questions ----
        VBox failuresBox = new VBox(6);
        failuresBox.setPadding(new Insets(12));
        if (failures.isEmpty()) {
            failuresBox.setManaged(false);
            failuresBox.setVisible(false);
        } else {
            failuresBox.setStyle("-fx-background-color: #fef2f2; -fx-border-color: #fecaca; "
                    + "-fx-border-radius: 8; -fx-background-radius: 8;");
            Label failuresTitle = new Label("⚠  " + failures.size()
                    + (failures.size() == 1 ? " question was skipped" : " questions were skipped")
                    + " (not imported)");
            failuresTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
            failuresTitle.setTextFill(Color.web("#b91c1c"));
            failuresBox.getChildren().add(failuresTitle);
            for (ParseFailure failure : failures) {
                Label item = new Label(failureLabel(failure));
                item.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
                item.setTextFill(Color.web("#b91c1c"));
                item.setWrapText(true);
                item.setMaxWidth(Double.MAX_VALUE);
                failuresBox.getChildren().add(item);
            }
        }

        // ---- The editable list of imported questions ----
        VBox listBox = new VBox(10);
        listBox.setAlignment(Pos.TOP_LEFT);

        // Going to the edit screen and coming back must show the list as it was left,
        // so the return trip rebuilds this screen from the same working list.
        final Runnable returnToPreview = () -> showImportPreviewScreen(stage, user, draftTest,
                draftQuestions, fileName, imported, failures, removedCount);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            listBox.getChildren().clear();

            if (imported.isEmpty()) {
                Label none = new Label(removedCount[0] == 0
                        ? "No questions were imported."
                        : "All imported questions were removed. Use Back to try another file.");
                none.setTextFill(Color.web("#94a3b8"));
                none.setWrapText(true);
                listBox.getChildren().add(none);
                return;
            }

            for (int i = 0; i < imported.size(); i++) {
                DraftQuestion q = imported.get(i);
                final int index = i;

                VBox row = new VBox(6);
                row.setPadding(new Insets(12, 14, 12, 14));
                row.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; "
                        + "-fx-border-radius: 8; -fx-background-radius: 8;");

                Label number = new Label("Q" + (index + 1) + "  ·  " + q.topic + "  ·  " + q.difficulty);
                number.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                number.setTextFill(Color.web("#7c3aed"));

                Label question = new Label(q.questionText);
                question.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
                question.setTextFill(Color.web("#1e293b"));
                question.setWrapText(true);
                question.setMaxWidth(Double.MAX_VALUE);

                Label options = new Label("A) " + q.optionA + "\nB) " + q.optionB
                        + "\nC) " + q.optionC + "\nD) " + q.optionD);
                options.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 12));
                options.setTextFill(Color.web("#475569"));
                options.setWrapText(true);
                options.setMaxWidth(Double.MAX_VALUE);

                Label answer = new Label("Correct answer: " + q.correctOption);
                answer.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                answer.setTextFill(Color.web("#16a34a"));

                Button editBtn = new Button("✏  Edit");
                editBtn.setPrefHeight(32);
                editBtn.setStyle("-fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; "
                        + "-fx-font-size: 12px; -fx-background-radius: 5; -fx-cursor: hand;");

                Button removeBtn = new Button("🗑  Remove");
                removeBtn.setPrefHeight(32);
                removeBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-weight: bold; "
                        + "-fx-font-size: 12px; -fx-background-radius: 5; -fx-cursor: hand;");

                editBtn.setOnAction(e -> showImportEditQuestionScreen(stage, user, draftTest,
                        draftQuestions, fileName, imported, returnToPreview, index));

                removeBtn.setOnAction(e -> {
                    if (index < imported.size()) {
                        imported.remove(index);
                        removedCount[0]++;
                    }
                    showMessage(messageLabel, "Question removed from this import.", true);
                    refresh[0].run();
                });

                HBox actions = new HBox(8, editBtn, removeBtn);
                actions.setAlignment(Pos.CENTER_RIGHT);

                row.getChildren().addAll(number, question, options, answer, actions);
                listBox.getChildren().add(row);
            }
        };
        refresh[0].run();

        // ---- Save ----
        Button confirmBtn = new Button("✓  Confirm & Save All");
        confirmBtn.setMaxWidth(Double.MAX_VALUE);
        confirmBtn.setPrefHeight(46);
        confirmBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 15px; -fx-background-radius: 6; -fx-cursor: hand;");

        Button cancelBtn = new Button("← Back to Questions");
        cancelBtn.setMaxWidth(Double.MAX_VALUE);
        cancelBtn.setPrefHeight(44);
        cancelBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> showWizardStep2QuestionScreen(stage, user, draftTest,
                draftQuestions, draftQuestions.size()));

        // Set once the test has been written, so a double click cannot create it twice.
        final boolean[] alreadySaved = { false };

        confirmBtn.setOnAction(e -> {
            if (alreadySaved[0]) {
                return;
            }
            if (imported.isEmpty()) {
                showMessage(messageLabel, "There is nothing to save - every imported question was removed.", false);
                return;
            }

            // Manual questions first (they were created first), then the imported ones
            // in file order.
            List<DraftQuestion> combined = new ArrayList<>(draftQuestions);
            combined.addAll(imported);

            // Re-check every question, manual and imported alike: editing can leave one
            // incomplete, and the wizard's Back button keeps a half-typed question in the
            // draft list. Checking only the imported rows let a blank manual question
            // reach the database, where it is stored happily and then shown to students
            // as four empty radio buttons.
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < combined.size(); i++) {
                DraftQuestion q = combined.get(i);
                String error = InputValidator.validateQuestion(q.questionText, q.optionA,
                        q.optionB, q.optionC, q.optionD);
                if (error == null) {
                    error = InputValidator.validateTopic(q.topic);
                }
                if (error != null) {
                    problems.add("Q" + (i + 1) + ": " + error);
                }
            }
            if (!problems.isEmpty()) {
                showMessage(messageLabel, "Please fix these before saving — " + String.join("  |  ", problems), false);
                return;
            }

            // Every question and the test itself are written on one connection in one
            // transaction. Using TestDAO.addQuestion() in a loop would instead commit
            // each question separately and could leave a half-saved test behind if one
            // failed - the exact problem createTestWithQuestions was added to fix.
            List<Question> questions = new ArrayList<>(combined.size());
            for (DraftQuestion q : combined) {
                questions.add(new Question(q.questionText, q.optionA, q.optionB, q.optionC,
                        q.optionD, q.correctOption,
                        q.topic == null || q.topic.isBlank() ? "General" : q.topic,
                        q.difficulty == null || q.difficulty.isBlank() ? "MEDIUM" : q.difficulty));
            }

            try {
                testDAO.createTestWithQuestions(
                        draftTest.title, draftTest.language, draftTest.timeMinutes * 60,
                        draftTest.expiryAction, user.getId(),
                        draftTest.classId, draftTest.isPublic, questions);
                alreadySaved[0] = true;

                String successMsg = String.format(Locale.ROOT,
                        "Test '%s' created successfully with %d questions imported from %s!",
                        draftTest.title, questions.size(), fileName);
                showTeacherDashboard(stage, user, successMsg);

            } catch (SQLException ex) {
                showMessage(messageLabel, "Failed to save the imported questions: " + ex.getMessage(), false);
            } catch (IllegalArgumentException ex) {
                // The DAO re-checks every question before writing; reaching this means a
                // draft slipped past the screen checks above.
                showMessage(messageLabel, ex.getMessage(), false);
            } catch (RuntimeException ex) {
                showMessage(messageLabel, "Unexpected problem while saving: " + ex.getMessage(), false);
            }
        });

        card.getChildren().addAll(headerBox, failuresBox, messageLabel, listBox, confirmBtn, cancelBtn);

        installScrollableScreen(stage, card, 800, 720);
    }

    /**
     * EDIT SCREEN: edit one imported question. Matches the wizard's one-item-per-page
     * pattern; saving returns to the preview with the change applied in place.
     *
     * <p>{@code onSaved} is what returns to the preview. It used to be the preview's
     * list-refresh runnable followed by a fresh {@link #showImportPreviewScreen} call,
     * which rebuilt the working list from the original parse result and so discarded the
     * very edit or removal being saved. It is now a single navigation that hands the same
     * list back to the preview.
     */
    private void showImportEditQuestionScreen(Stage stage, User user,
                                              DraftTestDetails draftTest,
                                              List<DraftQuestion> draftQuestions,
                                              String fileName,
                                              List<DraftQuestion> imported,
                                              Runnable onSaved,
                                              int index) {

        DraftQuestion q = imported.get(index);

        VBox form = new VBox(14);
        form.setMaxWidth(580);
        form.setAlignment(Pos.CENTER);
        form.setPadding(new Insets(26));
        form.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; "
                + "-fx-border-radius: 12; -fx-background-radius: 12; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 16, 0, 0, 4);");

        Label header = new Label("Edit Imported Question " + (index + 1));
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 21));
        header.setTextFill(Color.web("#7c3aed"));

        Label sub = new Label("From: " + fileName);
        sub.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
        sub.setTextFill(Color.web("#64748b"));

        VBox headerBox = new VBox(4, header, sub);
        headerBox.setAlignment(Pos.CENTER);

        TextField questionField = new TextField(q.questionText);
        questionField.setPromptText("Question Text");
        questionField.setStyle(INPUT_STYLE);
        questionField.setPrefHeight(42);

        TextField aField = new TextField(q.optionA);
        aField.setPromptText("Option A");
        aField.setStyle(INPUT_STYLE);
        aField.setPrefHeight(38);

        TextField bField = new TextField(q.optionB);
        bField.setPromptText("Option B");
        bField.setStyle(INPUT_STYLE);
        bField.setPrefHeight(38);

        TextField cField = new TextField(q.optionC);
        cField.setPromptText("Option C");
        cField.setStyle(INPUT_STYLE);
        cField.setPrefHeight(38);

        TextField dField = new TextField(q.optionD);
        dField.setPromptText("Option D");
        dField.setStyle(INPUT_STYLE);
        dField.setPrefHeight(38);

        ComboBox<String> correctCombo = new ComboBox<>();
        correctCombo.getItems().addAll("Option A", "Option B", "Option C", "Option D");
        switch (q.correctOption == null ? "A" : q.correctOption.toUpperCase(Locale.ROOT)) {
            case "B": correctCombo.setValue("Option B"); break;
            case "C": correctCombo.setValue("Option C"); break;
            case "D": correctCombo.setValue("Option D"); break;
            default:  correctCombo.setValue("Option A"); break;
        }
        correctCombo.setMaxWidth(Double.MAX_VALUE);
        correctCombo.setStyle(COMBO_STYLE);
        correctCombo.setPrefHeight(38);

        TextField topicField = new TextField(q.topic);
        topicField.setPromptText("Topic (e.g. Loops, OOP)");
        topicField.setStyle(INPUT_STYLE);
        topicField.setPrefHeight(38);

        ComboBox<String> difficultyCombo = new ComboBox<>();
        difficultyCombo.getItems().addAll("EASY", "MEDIUM", "HARD");
        difficultyCombo.setValue(q.difficulty != null ? q.difficulty : "MEDIUM");
        difficultyCombo.setMaxWidth(Double.MAX_VALUE);
        difficultyCombo.setStyle(COMBO_STYLE);
        difficultyCombo.setPrefHeight(38);

        Label messageLabel = new Label();
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);

        Button removeBtn = new Button("🗑  Remove");
        removeBtn.setPrefWidth(150);
        removeBtn.setPrefHeight(44);
        removeBtn.setStyle("-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        removeBtn.setOnAction(e -> {
            if (index < imported.size()) {
                imported.remove(index);
            }
            onSaved.run();
        });

        Button saveBtn = new Button("✓  Save Changes");
        saveBtn.setPrefWidth(200);
        saveBtn.setPrefHeight(44);
        saveBtn.setStyle("-fx-background-color: #7c3aed; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setPrefWidth(120);
        cancelBtn.setPrefHeight(44);
        cancelBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> onSaved.run());

        saveBtn.setOnAction(e -> {
            String error = InputValidator.validateQuestion(questionField.getText(), aField.getText(),
                    bField.getText(), cField.getText(), dField.getText());
            if (error == null) {
                error = InputValidator.validateTopic(topicField.getText());
            }
            if (error != null) {
                showMessage(messageLabel, error, false);
                return;
            }

            q.questionText = questionField.getText().trim();
            q.optionA = aField.getText().trim();
            q.optionB = bField.getText().trim();
            q.optionC = cField.getText().trim();
            q.optionD = dField.getText().trim();

            String selected = correctCombo.getValue();
            if ("Option B".equals(selected)) q.correctOption = "B";
            else if ("Option C".equals(selected)) q.correctOption = "C";
            else if ("Option D".equals(selected)) q.correctOption = "D";
            else q.correctOption = "A";

            q.topic = topicField.getText().trim().isEmpty() ? "General" : topicField.getText().trim();
            q.difficulty = difficultyCombo.getValue();

            if (index < imported.size()) {
                imported.set(index, q);
            }
            onSaved.run();
        });

        HBox navBox = new HBox(10, cancelBtn, removeBtn, saveBtn);
        navBox.setAlignment(Pos.CENTER);

        form.getChildren().addAll(
                headerBox,
                questionField,
                aField, bField, cField, dField,
                new Label("Correct Option:"), correctCombo,
                topicField,
                new Label("Difficulty:"), difficultyCombo,
                messageLabel,
                navBox
        );

        installScrollableScreen(stage, form, 750, 720);
    }

    /** Converts a parsed question into the wizard's mutable draft type. */
    private static DraftQuestion toDraftQuestion(ParsedQuestion parsed) {
        DraftQuestion draft = new DraftQuestion();
        draft.questionText = parsed.getQuestionText();
        draft.optionA = parsed.getOptionA();
        draft.optionB = parsed.getOptionB();
        draft.optionC = parsed.getOptionC();
        draft.optionD = parsed.getOptionD();
        draft.correctOption = parsed.getCorrectOption();
        draft.topic = parsed.getTopic();
        draft.difficulty = parsed.getDifficulty();
        return draft;
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

                    Label tDetails = new Label(String.format(Locale.ROOT, "Language: %s | Duration: %s | By: %s",
                            t.get("language"), InputValidator.formatDuration(toInt(t.get("totalTimeSeconds"))), t.get("teacherName")));
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

        installScrollableScreen(stage, rootBox, 750, 650);
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
            String cCode = InputValidator.normalizeClassCode(classCodeField.getText());

            String validationError = InputValidator.validateJoinClass(tUser, cCode);
            if (validationError != null) {
                showMessage(joinMsg, validationError, false);
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

        installScrollableScreen(stage, rootBox, 750, 680);
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

                    Label rScore = new Label(String.format(Locale.ROOT, "Score: %d / %d  |  Taken At: %s", score, total, r.get("takenAt")));
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

        installScrollableScreen(stage, rootBox, 750, 650);
    }

    // =========================================================================
    // TEST-TAKING & RESULT DISPLAY SCREENS
    // =========================================================================

    /**
     * Shows a single explanatory message with a way back to the student dashboard.
     *
     * <p>Used when a test cannot be started. Both of those paths previously failed
     * silently: one bounced the student straight back to the dashboard and the other
     * duplicated this same card inline.
     */
    private void showErrorScreen(Stage stage, User user, String message) {
        VBox box = new VBox(20);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(520);
        box.setPadding(new Insets(24));

        Label err = new Label(message);
        err.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 15));
        err.setTextFill(Color.web("#ef4444"));
        err.setWrapText(true);

        Button backBtn = new Button("Back to Dashboard");
        backBtn.setPrefHeight(44);
        backBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> showStudentDashboard(stage, user));

        box.getChildren().addAll(err, backBtn);
        setScreenRoot(stage, createCenteredRoot(box), 750, 550);
    }

    /**
     * TEST-TAKING SCREEN
     */
    private void showTestTakingScreen(Stage stage, User user, Map<String, Object> testMap) {
        int testId = toInt(testMap.get("id"));
        String testTitle = (String) testMap.get("title");
        String language = (String) testMap.get("language");
        int totalTimeSeconds = toInt(testMap.get("totalTimeSeconds"));

        // A row whose expiry_action is NULL (written before that column existed) used
        // to fail the "AUTO_SUBMIT".equalsIgnoreCase(...) test and silently become
        // unlimited overtime. Auto-submit is now the default unless the value says
        // otherwise.
        boolean allowOvertime = "ALLOW_OVERTIME".equalsIgnoreCase((String) testMap.get("expiryAction"));

        boolean isUrdu = "Urdu".equalsIgnoreCase(language);
        NodeOrientation textOrientation = isUrdu ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;

        List<Map<String, Object>> questions;
        try {
            questions = testDAO.getQuestionsByTestId(testId);
        } catch (SQLException ex) {
            // The student used to be dropped back on the dashboard with no message,
            // so a database problem looked like the test had simply vanished.
            showErrorScreen(stage, user, "Could not load this test: " + ex.getMessage());
            return;
        }

        if (questions.isEmpty()) {
            showErrorScreen(stage, user, "This test does not contain any questions yet.");
            return;
        }

        Map<Integer, String> studentAnswers = new HashMap<>();
        int[] remainingSecondsHolder = new int[]{ totalTimeSeconds };
        int[] overtimeSecondsHolder = new int[]{ 0 };
        boolean[] isOvertimeActive = new boolean[]{ false };
        boolean[] isSubmittedHolder = new boolean[]{ false };

        Label timerLabel = new Label();
        timerLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));

        // Starting a test replaces any countdown still running from an earlier test, so a
        // student can never end up with two timers competing over one window.
        stopQuizTimer();

        Timeline timerTimeline = new Timeline();
        timerTimeline.setCycleCount(Timeline.INDEFINITE);
        quizTimerTimeline = timerTimeline;

        Runnable updateTimerDisplay = () -> {
            if (!isOvertimeActive[0]) {
                int secs = remainingSecondsHolder[0];
                int m = secs / 60;
                int s = secs % 60;
                timerLabel.setText(String.format(Locale.ROOT, "⏱ Time Remaining: %02d:%02d", m, s));
                timerLabel.setTextFill(Color.web("#0284c7"));
            } else {
                int secs = overtimeSecondsHolder[0];
                int m = secs / 60;
                int s = secs % 60;
                timerLabel.setText(String.format(Locale.ROOT, "⚠️ Overtime: +%02d:%02d", m, s));
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

            // An empty catch block here used to show a normal result screen even when
            // the attempt had not been recorded, so the student believed the score was
            // saved and the teacher never saw it.
            String saveWarning = null;
            try {
                resultDAO.saveResult(testId, user.getId(), score, total, finalOvertime);
            } catch (SQLException ex) {
                saveWarning = "Warning: your score could not be saved to the database ("
                        + ex.getMessage() + "). Please tell your teacher.";
                System.err.println("Failed to save result for test " + testId
                        + ", student " + user.getId() + ": " + ex.getMessage());
            }

            showResultScreen(stage, user, testTitle, score, total, finalOvertime,
                    questions, studentAnswers, saveWarning);
        };

        KeyFrame kf = new KeyFrame(Duration.seconds(1), event -> {
            if (!isOvertimeActive[0]) {
                remainingSecondsHolder[0]--;
                if (remainingSecondsHolder[0] <= 0) {
                    if (allowOvertime) {
                        isOvertimeActive[0] = true;
                        overtimeSecondsHolder[0] = 0;
                    } else {
                        performSubmit.run();
                        return;
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

        installScrollableScreen(stage, card, 800, 650);
    }

    /**
     * RESULT SCREEN WITH DETAILED ANSWER REVIEW
     */
    private void showResultScreen(Stage stage, User user, String testTitle, int score, int total, 
                                  int overtimeSeconds, List<Map<String, Object>> questions, 
                                  Map<Integer, String> studentAnswers, String saveWarning) {
        VBox rootBox = new VBox(20);
        rootBox.setMaxWidth(620);
        rootBox.setAlignment(Pos.CENTER);

        Label header = new Label("Test Results");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));
        header.setTextFill(Color.web("#1e293b"));

        Label tTitle = new Label("Test: " + testTitle);
        tTitle.setFont(Font.font("Segoe UI", FontWeight.MEDIUM, 15));
        tTitle.setTextFill(Color.web("#64748b"));

        Label scoreDisplay = new Label(String.format(Locale.ROOT, "You scored %d out of %d", score, total));
        scoreDisplay.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        scoreDisplay.setTextFill(Color.web("#10b981"));

        double percentage = total > 0 ? ((double) score / total) * 100 : 0;
        Label percLabel = new Label(String.format(Locale.ROOT, "Score Percentage: %.1f%%", percentage));
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

        if (saveWarning != null) {
            Label warnLabel = new Label(saveWarning);
            warnLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
            warnLabel.setTextFill(Color.web("#b91c1c"));
            warnLabel.setWrapText(true);
            scoreCard.getChildren().add(warnLabel);
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

            Label qText = new Label(String.format(Locale.ROOT, "Q%d. %s", (i + 1), q.get("questionText")));
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

        installScrollableScreen(stage, rootBox, 800, 700);
    }

    private String formatOptionText(Map<String, Object> questionMap, String key) {
        if (key == null || questionMap == null) return "None";
        Object val;
        // Locale.ROOT so the mapping is not affected by the default locale.
        switch (key.toUpperCase(Locale.ROOT)) {
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