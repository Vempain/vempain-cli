package fi.poltsi.vempain.cli.tui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AsynchronousTextGUIThread;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.SeparateTextGUIThread;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;

/**
 * Full-screen Lanterna interface of the interactive shell. The session status (which backends the user is logged in to) is shown in the upper left corner and
 * the running background tasks of the logged-in backends in the upper right corner; command output fills the middle and the command line sits at the bottom.
 * <p>
 * Threads: Lanterna owns the GUI thread, commands run one at a time on a command thread so the screen stays live while they wait for a backend, and a poller
 * thread refreshes the status and tasks windows every poll interval and right after each command.
 */
public class TuiShell {

    private static final int TOP_ROWS = 6;
    private static final int STATUS_COLUMNS = 34;
    private static final int MAX_OUTPUT_LINES = 2000;
    private static final String HINT = "Enter: run   Tab: complete   Up/Down: history   PgUp/PgDn: scroll output   exit: quit";

    private final SessionStore sessionStore;
    private final TaskBoard taskBoard;
    private final Duration pollInterval;
    private final List<String> history;
    private final TerminalScreen screen;
    private final MultiWindowTextGUI gui;
    private final BasicWindow window;
    private final TextBox output;
    private final CommandInput input;
    private final Label promptLabel;
    private final Label tasksLabel;
    private final StatusRow fileStatus;
    private final StatusRow adminStatus;
    private final Label activeLabel;
    private final LineSink outSink;
    private final LineSink errSink;
    private final PrintStream out;
    private final PrintStream err;

    private final ExecutorService commandRunner;
    private final ScheduledExecutorService poller;

    private Predicate<String> lineHandler;
    private Supplier<String> prompt;
    private Completer completer;
    private boolean outputEmpty = true;
    private boolean followOutput = true;
    private boolean busy;
    private int historyIndex;

    /**
     * @param history earlier commands, oldest first, recalled with the Up/Down keys
     */
    public TuiShell(Terminal terminal, SessionStore sessionStore, TaskClient taskClient, List<String> history, Duration pollInterval) throws IOException {
        this.sessionStore = sessionStore;
        this.taskBoard = new TaskBoard(sessionStore, taskClient);
        this.pollInterval = pollInterval;
        this.history = new ArrayList<>(history);
        this.historyIndex = this.history.size();
        this.outSink = new LineSink(this::appendOutput);
        this.errSink = new LineSink(this::appendOutput);
        this.out = new PrintStream(outSink, true, StandardCharsets.UTF_8);
        this.err = new PrintStream(errSink, true, StandardCharsets.UTF_8);

        this.screen = new TerminalScreen(terminal);
        this.gui = new MultiWindowTextGUI(new SeparateTextGUIThread.Factory(), screen, new DefaultWindowManager(), null,
                new EmptySpace(TextColor.ANSI.DEFAULT));

        this.fileStatus = new StatusRow("File backend");
        this.adminStatus = new StatusRow("Admin backend");
        this.activeLabel = new Label("none");
        var statusPanel = new Panel(new GridLayout(2).setHorizontalSpacing(1)
            .setLeftMarginSize(0)
            .setRightMarginSize(0));
        fileStatus.addTo(statusPanel);
        adminStatus.addTo(statusPanel);
        statusPanel.addComponent(new Label("Active backend"));
        statusPanel.addComponent(activeLabel);
        statusPanel.setPreferredSize(new TerminalSize(STATUS_COLUMNS, TOP_ROWS));

        this.tasksLabel = new Label("");
        tasksLabel.setPreferredSize(new TerminalSize(1, TOP_ROWS));

        this.output = new TextBox(new TerminalSize(1, 1), TextBox.Style.MULTI_LINE);
        output.setReadOnly(true);

        this.promptLabel = new Label("");
        this.input = new CommandInput();
        var inputRow = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
        inputRow.addComponent(promptLabel);
        inputRow.addComponent(input, LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));

        // The top row has its own grid so that only the tasks window takes the width the session window leaves free.
        var topRow = new Panel(new GridLayout(2).setHorizontalSpacing(0)
            .setLeftMarginSize(0)
            .setRightMarginSize(0));
        topRow.addComponent(statusPanel.withBorder(Borders.singleLine("Session")),
                GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.FILL, false, false));
        topRow.addComponent(tasksLabel.withBorder(Borders.singleLine("Running tasks")),
                GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, false));

        var root = new Panel(new GridLayout(1).setLeftMarginSize(0)
            .setRightMarginSize(0));
        root.addComponent(topRow, GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.BEGINNING, true, false));
        root.addComponent(output.withBorder(Borders.singleLine("Output")),
                GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true));
        root.addComponent(inputRow, GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.CENTER, true, false));
        root.addComponent(new Label(HINT).setForegroundColor(TextColor.ANSI.BLUE),
                GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.CENTER, true, false));

        this.window = new BasicWindow();
        window.setHints(List.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS));
        window.setComponent(root);
        window.setFocusedInteractable(input);

        var commandThreadFactory = Thread.ofPlatform()
            .daemon()
            .name("vf-cli-command")
            .factory();
        var pollerThreadFactory = Thread.ofPlatform()
            .daemon()
            .name("vf-cli-task-poller")
            .factory();
        this.commandRunner = Executors.newSingleThreadExecutor(commandThreadFactory);
        this.poller = Executors.newSingleThreadScheduledExecutor(pollerThreadFactory);
    }

    /**
     * Opens the user's terminal in text mode; never falls back to a Swing terminal emulator window.
     */
    public static Terminal createTerminal() throws IOException {
        var factory = new DefaultTerminalFactory(System.out, System.in, StandardCharsets.UTF_8);
        return factory.setForceTextTerminal(true)
            .createTerminal();
    }

    /**
     * @return whether both stdin and stdout are attached to a terminal, i.e. whether the TUI can be shown
     */
    public static boolean isInteractiveTerminal() {
        var console = System.console();
        return console != null
               && console.isTerminal();
    }

    /**
     * Command output stream; everything printed here is shown in the output window.
     */
    public PrintStream out() {
        return out;
    }

    public PrintStream err() {
        return err;
    }

    /**
     * Password prompt shown as a modal dialog. Must be called from the command thread, never from the GUI thread.
     */
    public PasswordReader passwordReader() {
        return this::readPassword;
    }

    public TaskMonitor taskMonitor(TaskClient taskClient) {
        return new TuiTaskMonitor(taskClient, out, this::refreshNow);
    }

    /**
     * Shows the TUI and blocks until the user leaves it.
     *
     * @param lineHandler runs one command line; returns {@code false} when the line ends the shell ({@code exit}/{@code quit})
     * @param prompt      prompt shown in front of the command line, re-read after every command
     * @param completer   tab completion for the command line
     */
    public void run(Predicate<String> lineHandler, Supplier<String> prompt, Completer completer) throws IOException, InterruptedException {
        this.lineHandler = lineHandler;
        this.prompt = prompt;
        this.completer = completer;

        screen.startScreen();
        try {
            promptLabel.setText(prompt.get());
            gui.addWindow(window);
            var guiThread = (AsynchronousTextGUIThread) gui.getGUIThread();
            guiThread.setExceptionHandler(new TextGUIThread.ExceptionHandler() {

                @Override
                public boolean onIOException(IOException e) {
                    return true;
                }

                @Override
                public boolean onRuntimeException(RuntimeException e) {
                    appendOutput("UI error: " + e.getMessage());
                    return false;
                }
            });
            guiThread.start();
            poller.scheduleWithFixedDelay(this::poll, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
            gui.waitForWindowToClose(window);
            guiThread.stop();
            guiThread.waitForStop(1, TimeUnit.SECONDS);
        } finally {
            poller.shutdownNow();
            commandRunner.shutdownNow();
            screen.stopScreen();
        }
    }

    private void submit(String text) {
        if (busy) {
            appendOutput("A command is still running, wait for it to finish.");
            return;
        }
        var line = text.trim();
        input.setText("");
        appendOutput(promptLabel.getText() + line);
        if (line.isEmpty()) {
            return;
        }
        if (history.isEmpty()
            || !history.getLast()
                .equals(line)) {
            history.add(line);
        }
        historyIndex = history.size();
        busy = true;
        promptLabel.setText("running... ");
        commandRunner.execute(() -> runCommand(line));
    }

    private void runCommand(String line) {
        var keepRunning = true;
        try {
            keepRunning = lineHandler.test(line);
        } catch (RuntimeException e) {
            err.println("Error: " + e.getMessage());
        }
        outSink.drain();
        errSink.drain();
        var stay = keepRunning;
        onGui(() -> {
            busy = false;
            if (!stay) {
                window.close();
                return;
            }
            promptLabel.setText(prompt.get());
        });
        if (stay) {
            refreshNow();
        }
    }

    private void complete() {
        if (completer == null) {
            return;
        }
        var text = input.getText();
        try {
            var parsed = new DefaultParser().parse(text, text.length(), Parser.ParseContext.COMPLETE);
            var word = parsed.word();
            var candidates = new ArrayList<Candidate>();
            completer.complete(null, parsed, candidates);
            var matches = new LinkedHashMap<String, Candidate>();
            candidates.stream()
                .filter(candidate -> candidate.value()
                    .startsWith(word))
                .forEach(candidate -> matches.putIfAbsent(candidate.value(), candidate));
            if (matches.isEmpty()) {
                return;
            }
            var head = text.substring(0, parsed.cursor() - parsed.wordCursor());
            if (matches.size() == 1) {
                var match = matches.values()
                    .iterator()
                    .next();
                setInput(head + quoted(head, match.value(), match.complete()) + (match.complete() ? " " : ""));
                return;
            }
            var common = commonPrefix(List.copyOf(matches.keySet()));
            if (common.length() > word.length()) {
                setInput(head + quoted(head, common, false));
            }
            appendOutput(matches.values()
                .stream()
                .map(Candidate::displ)
                .collect(Collectors.joining("  ")));
        } catch (RuntimeException e) {
            appendOutput("Completion failed: " + e.getMessage());
        }
    }

    /**
     * A completed word containing whitespace (a local file name, for instance) is put in double quotes, because the command line is split on whitespace outside
     * quotes. A word the user already opened with a quote keeps it; the closing quote is added once the word is complete.
     */
    private static String quoted(String head, String value, boolean complete) {
        var opened = head.endsWith("\"");
        if (!opened
            && value.chars()
                .noneMatch(Character::isWhitespace)) {
            return value;
        }
        return (opened ? "" : "\"") + value + (complete ? "\"" : "");
    }

    private void recall(int step) {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.clamp(historyIndex + step, 0, history.size());
        setInput(historyIndex == history.size() ? "" : history.get(historyIndex));
    }

    /**
     * Scrolls the output window one page. Lanterna moves the view of a read-only text box only while the box itself has the focus, and the focus stays on the
     * command line, so the view is moved here. Scrolling back to the end follows new output again.
     */
    private void scrollOutput(int direction) {
        var rows = output.getSize()
            .getRows();
        var page = Math.max(1, rows - 1);
        var lastTop = Math.max(0, output.getLineCount() - rows);
        var view = output.getRenderer()
            .getViewTopLeft();
        var top = Math.clamp(Math.min(view.getRow(), lastTop) + (long) direction * page, 0, lastTop);
        followOutput = top >= lastTop;
        showOutputFrom(followOutput ? output.getLineCount() : top);
    }

    /**
     * @param top first output line to show; a row past the end shows the last lines, as the renderer pulls the view back to the text
     */
    private void showOutputFrom(int top) {
        var renderer = output.getRenderer();
        renderer.setViewTopLeft(renderer.getViewTopLeft()
            .withRow(top));
        output.invalidate();
    }

    private void setInput(String text) {
        input.setText(text);
        input.setCaretPosition(text.length());
    }

    private char[] readPassword(String promptText) {
        var dialog = new PasswordDialog(promptText);
        try {
            gui.getGUIThread()
                .invokeAndWait(() -> gui.addWindow(dialog));
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException("Password prompt interrupted", e);
        }
        gui.waitForWindowToClose(dialog);
        var password = dialog.password();
        if (password == null) {
            throw new IllegalStateException("Login cancelled");
        }
        return password;
    }

    private void refreshNow() {
        try {
            poller.execute(this::poll);
        } catch (RejectedExecutionException ignored) {
            // The shell is closing.
        }
    }

    private void poll() {
        try {
            var file = sessionStore.load(SessionStore.BACKEND_FILE);
            var admin = sessionStore.load(SessionStore.BACKEND_ADMIN);
            var active = sessionStore.getActiveBackend();
            var tasks = TaskBoard.lines(taskBoard.refresh());
            onGui(() -> {
                fileStatus.show(file);
                adminStatus.show(admin);
                var activeSession = SessionStore.BACKEND_ADMIN.equals(active) ? admin : file;
                activeLabel.setText(activeSession == null ? "none" : active);
                showTasks(tasks);
            });
        } catch (RuntimeException e) {
            // Keep polling; a failing refresh must not stop the scheduled task.
            appendOutput("Task refresh failed: " + e.getMessage());
        }
    }

    private void showTasks(List<String> lines) {
        var size = tasksLabel.getSize();
        tasksLabel.setText(String.join("\n", fit(lines, size.getColumns(), Math.max(TOP_ROWS, size.getRows()))));
    }

    private void appendOutput(String line) {
        onGui(() -> {
            if (outputEmpty) {
                output.setText(line);
                outputEmpty = false;
            } else {
                output.addLine(line);
            }
            var removed = 0;
            while (output.getLineCount() > MAX_OUTPUT_LINES) {
                output.removeLine(0);
                removed++;
            }
            // While the user has scrolled back, keep the same lines in view; otherwise follow the newest line.
            var outputStart = followOutput ? output.getLineCount()
                    : Math.max(0, output.getRenderer()
                        .getViewTopLeft()
                        .getRow() - removed);
            showOutputFrom(outputStart);
        });
    }

    private void onGui(Runnable action) {
        try {
            gui.getGUIThread()
                .invokeLater(action);
        } catch (IllegalStateException ignored) {
            // The GUI thread has stopped; nothing is shown any more.
        }
    }

    static List<String> fit(List<String> lines, int columns, int rows) {
        var visible = lines.size() <= rows ? lines : new ArrayList<>(lines.subList(0, rows - 1));
        if (visible.size() < lines.size()) {
            visible.add("... and " + (lines.size() - visible.size()) + " more");
        }
        return visible.stream()
            .map(line -> truncate(line, columns))
            .toList();
    }

    private static String truncate(String value, int width) {
        if (value == null) {
            return "";
        }
        if (width <= 0
            || value.length() <= width) {
            return value;
        }
        return value.substring(0, Math.max(0, width - 3)) + "...";
    }

    private static String commonPrefix(List<String> values) {
        var prefix = values.getFirst();
        for (var value : values) {
            var length = 0;
            while (length < prefix.length()
                   && length < value.length()
                   && prefix.charAt(length) == value.charAt(length)) {
                length++;
            }
            prefix = prefix.substring(0, length);
        }
        return prefix;
    }

    /**
     * One backend in the session window: its name and login state, and the base URL below them while logged in.
     */
    private static final class StatusRow {

        private final Label name;
        private final Label state = new Label("");
        private final Label url = new Label("");

        private StatusRow(String name) {
            this.name = new Label(name);
        }

        private void addTo(Panel panel) {
            panel.addComponent(name);
            panel.addComponent(state);
            panel.addComponent(url, GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.CENTER, true, false, 2, 1));
        }

        private void show(SessionStore.Session session) {
            if (session == null) {
                state.setText("not logged in");
                state.setForegroundColor(TextColor.ANSI.RED);
                url.setText("");
                return;
            }
            state.setText("logged in");
            state.setForegroundColor(TextColor.ANSI.GREEN);
            url.setText(truncate("  " + session.baseUrl(), STATUS_COLUMNS));
        }
    }

    private final class CommandInput extends TextBox {

        private CommandInput() {
            super(new TerminalSize(1, 1), "", Style.SINGLE_LINE);
        }

        @Override
        public synchronized Result handleKeyStroke(KeyStroke key) {
            switch (key.getKeyType()) {
                case Enter -> submit(getText());
                case Tab -> complete();
                case ArrowUp -> recall(-1);
                case ArrowDown -> recall(1);
                case PageUp -> scrollOutput(-1);
                case PageDown -> scrollOutput(1);
                default -> {
                    if (key.getKeyType() == KeyType.Character
                        && key.isCtrlDown()
                        && key.getCharacter() == 'd'
                        && getText().isEmpty()) {
                        submit("exit");
                        return Result.HANDLED;
                    }
                    return super.handleKeyStroke(key);
                }
            }
            return Result.HANDLED;
        }
    }

    private static final class PasswordDialog extends BasicWindow {

        private char[] password;

        private PasswordDialog(String promptText) {
            super("Login");
            setHints(List.of(Hint.MODAL, Hint.CENTERED));
            setCloseWindowWithEscape(true);
            var field = new TextBox(new TerminalSize(32, 1)) {

                @Override
                public synchronized Result handleKeyStroke(KeyStroke key) {
                    if (key.getKeyType() == KeyType.Enter) {
                        password = getText().toCharArray();
                        setText("");
                        PasswordDialog.this.close();
                        return Result.HANDLED;
                    }
                    return super.handleKeyStroke(key);
                }
            };
            field.setMask('*');
            var panel = new Panel(new LinearLayout(Direction.VERTICAL));
            panel.addComponent(new Label(promptText.strip()));
            panel.addComponent(field);
            panel.addComponent(new Label("Enter: log in   Esc: cancel"));
            setComponent(panel);
            setFocusedInteractable(field);
        }

        private char[] password() {
            return password;
        }
    }

    /**
     * Splits command output into lines for the output window. Partial lines wait for their newline, or for {@link #drain()} once the command has finished.
     */
    private static final class LineSink extends OutputStream {

        private final Consumer<String> target;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        private LineSink(Consumer<String> target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else if (b != '\r') {
                pending.write(b);
            }
        }

        private synchronized void drain() {
            if (pending.size() > 0) {
                emit();
            }
        }

        private void emit() {
            target.accept(pending.toString(StandardCharsets.UTF_8)
                .replace("\t", "    "));
            pending.reset();
        }
    }
}
