import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Органайзер: задачи, контакты и заметки в одном файле.
 *
 * Acceptance fixture for the Java Blueprints parser. It is a single-file "monolith" in the style
 * of the hackathon brief: core syntax, java.lang, console I/O, java.util collections, exceptions.
 * The code is compiled by the tests but never executed.
 */
public class Organizer {

    // ------------------------------------------------------------------ exceptions

    /** Базовое проверяемое исключение предметной области. */
    public static class OrganizerException extends Exception {
        private static final long serialVersionUID = 1L;

        private final int code;

        public OrganizerException(String message, int code) {
            super(message);
            this.code = code;
        }

        public OrganizerException(String message, int code, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    /** Ошибка проверки данных пользователя. */
    public static class ValidationException extends OrganizerException {
        private static final long serialVersionUID = 1L;

        private final String field;

        public ValidationException(String field, String message) {
            super(field + ": " + message, 400);
            this.field = field;
        }

        public String getField() {
            return field;
        }
    }

    /** Непроверяемое исключение: запись не найдена. */
    public static class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotFoundException(String what, int id) {
            super(what + " #" + id + " не найден");
        }
    }

    // ------------------------------------------------------------------ enums

    public enum Priority {
        LOW(1), NORMAL(2), HIGH(3), URGENT(5);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        public int getWeight() {
            return weight;
        }

        public Priority escalate() {
            switch (this) {
                case LOW:
                    return NORMAL;
                case NORMAL:
                    return HIGH;
                case HIGH:
                case URGENT:
                    return URGENT;
                default:
                    throw new IllegalStateException("unreachable");
            }
        }
    }

    public enum Status {
        TODO, IN_PROGRESS, DONE, CANCELLED;

        public boolean isOpen() {
            return this == TODO || this == IN_PROGRESS;
        }

        public String title() {
            return switch (this) {
                case TODO -> "К выполнению";
                case IN_PROGRESS -> "В работе";
                case DONE -> "Готово";
                case CANCELLED -> "Отменено";
            };
        }
    }

    // ------------------------------------------------------------------ interfaces and records

    public interface Identifiable {
        int id();

        default String label() {
            return "#" + id();
        }
    }

    public interface Repository<T extends Identifiable> {
        void save(T item);

        T get(int id);

        List<T> all();

        default boolean exists(int id) {
            for (T item : all()) {
                if (item.id() == id) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Contact(int id, String name, String email, String city) implements Identifiable {
        public Contact {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }

        @Override
        public String label() {
            return name + " <" + email + ">";
        }
    }

    public record Note(int id, String title, String body, List<String> tags) implements Identifiable {
    }

    // ------------------------------------------------------------------ model

    public static class Task implements Identifiable, Comparable<Task> {
        private final int id;
        private final int contactId;
        private String title;
        private Priority priority;
        private Status status = Status.TODO;
        private int estimateMinutes;
        private final List<String> tags = new ArrayList<>();

        public Task(int id, int contactId, String title, Priority priority, int estimateMinutes) {
            this.id = id;
            this.contactId = contactId;
            this.title = title;
            this.priority = priority;
            this.estimateMinutes = estimateMinutes;
        }

        @Override
        public int id() {
            return id;
        }

        public int getContactId() {
            return contactId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Priority getPriority() {
            return priority;
        }

        public void setPriority(Priority priority) {
            this.priority = priority;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public int getEstimateMinutes() {
            return estimateMinutes;
        }

        public List<String> getTags() {
            return tags;
        }

        @Override
        public int compareTo(Task other) {
            int byPriority = Integer.compare(other.priority.getWeight(), priority.getWeight());
            if (byPriority != 0) {
                return byPriority;
            }
            return Integer.compare(id, other.id);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Task other)) {
                return false;
            }
            return id == other.id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return String.format("Task[%d, %s, %s, %s]", id, title, priority, status);
        }
    }

    public static abstract class Reminder {
        protected final Task task;
        private static int created;

        static {
            created = 0;
        }

        {
            created++;
        }

        protected Reminder(Task task) {
            this.task = task;
        }

        public static int createdCount() {
            return created;
        }

        public abstract String render();

        public boolean isDue(int minutesLeft) {
            return minutesLeft <= threshold();
        }

        protected int threshold() {
            return 15;
        }
    }

    public static class PopupReminder extends Reminder {
        public PopupReminder(Task task) {
            super(task);
        }

        @Override
        public String render() {
            return "Напоминание: " + task.getTitle();
        }
    }

    public static class UrgentReminder extends PopupReminder {
        public UrgentReminder(Task task) {
            super(task);
        }

        @Override
        protected int threshold() {
            return 60;
        }

        @Override
        public String render() {
            return super.render().toUpperCase() + "!!!";
        }
    }

    // ------------------------------------------------------------------ repositories

    public static class MemoryRepository<T extends Identifiable> implements Repository<T> {
        private final Map<Integer, T> items = new HashMap<>();

        @Override
        public void save(T item) {
            items.put(item.id(), item);
        }

        @Override
        public T get(int id) {
            T item = items.get(id);
            if (item == null) {
                throw new NotFoundException("Запись", id);
            }
            return item;
        }

        @Override
        public List<T> all() {
            List<T> result = new ArrayList<>(items.values());
            result.sort(Comparator.comparingInt(Identifiable::id));
            return result;
        }
    }

    // ------------------------------------------------------------------ service

    private final Repository<Task> tasks = new MemoryRepository<>();
    private final Repository<Contact> contacts = new MemoryRepository<>();
    private final List<Note> notes = new ArrayList<>();
    private final Deque<String> history = new ArrayDeque<>();
    private final Set<String> knownTags = new HashSet<>();
    private int nextId = 1;

    public int addContact(String name, String email, String city) throws ValidationException {
        if (email == null || !email.contains("@")) {
            throw new ValidationException("email", "ожидался адрес вида user@host");
        }
        int id = nextId++;
        contacts.save(new Contact(id, name, email, city));
        history.push("contact:" + id);
        return id;
    }

    public int addTask(int contactId, String title, Priority priority, int estimate) throws OrganizerException {
        if (title == null || title.trim().isEmpty()) {
            throw new ValidationException("title", "название не может быть пустым");
        }
        if (estimate < 0 || estimate > 24 * 60) {
            throw new ValidationException("estimate", "оценка вне диапазона: " + estimate);
        }
        try {
            contacts.get(contactId);
        } catch (NotFoundException e) {
            throw new OrganizerException("Неизвестный контакт " + contactId, 404, e);
        }
        int id = nextId++;
        Task task = new Task(id, contactId, title.trim(), priority, estimate);
        tasks.save(task);
        history.push("task:" + id);
        return id;
    }

    public void tag(int taskId, String... newTags) {
        Task task = tasks.get(taskId);
        for (String tag : newTags) {
            String normalized = tag.toLowerCase().trim();
            if (!normalized.isEmpty() && !task.getTags().contains(normalized)) {
                task.getTags().add(normalized);
                knownTags.add(normalized);
            }
        }
    }

    public void addNote(String title, String body, String... tags) {
        notes.add(new Note(nextId++, title, body, Arrays.asList(tags)));
    }

    /**
     * Связывает каждую задачу с её контактом, просматривая список контактов заново для каждой
     * задачи. Для n задач и m контактов это O(n*m): ключ поиска - идентификатор контакта.
     */
    public int linkTasksToContacts(List<Task> taskList, List<Contact> contactList, Map<Integer, String> owners) {
        int linked = 0;
        for (Task task : taskList) {
            for (Contact contact : contactList) {
                if (contact.id() == task.getContactId()) {
                    owners.put(task.id(), contact.name());
                    linked++;
                    break;
                }
            }
        }
        return linked;
    }

    /** The same lookup, built from a Map: O(n + m). */
    public int linkTasksIndexed(List<Task> taskList, List<Contact> contactList, Map<Integer, String> owners) {
        Map<Integer, Contact> byId = new HashMap<>();
        for (Contact contact : contactList) {
            byId.putIfAbsent(contact.id(), contact);
        }
        int linked = 0;
        for (Task task : taskList) {
            Contact owner = byId.get(task.getContactId());
            if (owner != null) {
                owners.put(task.id(), owner.name());
                linked++;
            }
        }
        return linked;
    }

    /** Duplicate titles by index loops: quadratic, no lookup shape on a Map. */
    public List<String> findDuplicateTitles(List<Task> taskList) {
        List<String> duplicates = new ArrayList<>();
        for (int i = 0; i < taskList.size(); i++) {
            for (int j = i + 1; j < taskList.size(); j++) {
                if (taskList.get(i).getTitle().equals(taskList.get(j).getTitle())) {
                    duplicates.add(taskList.get(i).getTitle());
                }
            }
        }
        return duplicates;
    }

    public Map<Priority, List<Task>> groupByPriority() {
        Map<Priority, List<Task>> groups = new TreeMap<>();
        for (Task task : tasks.all()) {
            groups.computeIfAbsent(task.getPriority(), p -> new ArrayList<>()).add(task);
        }
        return groups;
    }

    public int totalOpenMinutes() {
        int total = 0;
        for (Task task : tasks.all()) {
            if (task.getStatus().isOpen()) {
                total += task.getEstimateMinutes();
            }
        }
        return total;
    }

    public Task nextTask() {
        PriorityQueue<Task> queue = new PriorityQueue<>();
        for (Task task : tasks.all()) {
            if (task.getStatus() == Status.TODO) {
                queue.add(task);
            }
        }
        return queue.poll();
    }

    public List<Task> search(Predicate<Task> filter) {
        List<Task> found = new LinkedList<>();
        Iterator<Task> it = tasks.all().iterator();
        while (it.hasNext()) {
            Task task = it.next();
            if (filter.test(task)) {
                found.add(task);
            }
        }
        return found;
    }

    public String undo() {
        if (history.isEmpty()) {
            return null;
        }
        return history.pop();
    }

    public void complete(int taskId) {
        Task task = tasks.get(taskId);
        switch (task.getStatus()) {
            case TODO:
            case IN_PROGRESS:
                task.setStatus(Status.DONE);
                break;
            case DONE:
                break;
            default:
                throw new IllegalStateException("cannot complete " + task);
        }
    }

    // ------------------------------------------------------------------ algorithms

    public static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    public static int digitSum(int value) {
        int sum = 0;
        int rest = Math.abs(value);
        do {
            sum += rest % 10;
            rest /= 10;
        } while (rest > 0);
        return sum;
    }

    public static int binarySearch(int[] sorted, int key) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] < key) {
                low = mid + 1;
            } else if (sorted[mid] > key) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    public static int halvings(int n) {
        int steps = 0;
        while (n > 1) {
            n /= 2;
            steps++;
        }
        return steps;
    }

    public static void bubbleSort(int[] data) {
        for (int i = 0; i < data.length - 1; i++) {
            boolean swapped = false;
            for (int j = 0; j < data.length - 1 - i; j++) {
                if (data[j] > data[j + 1]) {
                    int tmp = data[j];
                    data[j] = data[j + 1];
                    data[j + 1] = tmp;
                    swapped = true;
                }
            }
            if (!swapped) {
                break;
            }
        }
    }

    public static long[][] multiply(long[][] a, long[][] b, int n) {
        long[][] c = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    c[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return c;
    }

    public static boolean[] sieve(int limit) {
        boolean[] composite = new boolean[limit + 1];
        for (int p = 2; p * p <= limit; p++) {
            if (!composite[p]) {
                for (int m = p * p; m <= limit; m += p) {
                    composite[m] = true;
                }
            }
        }
        return composite;
    }

    public static long fibonacci(int n) {
        if (n < 2) {
            return n;
        }
        long previous = 0;
        long current = 1;
        for (int i = 2; i <= n; i++) {
            long next = previous + current;
            previous = current;
            current = next;
        }
        return current;
    }

    public static int fixedGrid() {
        int cells = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                cells += (row + col) % 2;
            }
        }
        return cells;
    }

    public static boolean isPalindrome(String text) {
        int left = 0;
        int right = text.length() - 1;
        while (left < right) {
            if (Character.toLowerCase(text.charAt(left)) != Character.toLowerCase(text.charAt(right))) {
                return false;
            }
            left++;
            right--;
        }
        return true;
    }

    public static int findFirstNegative(int[][] grid) {
        int found = -1;
        outer:
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[r].length; c++) {
                if (grid[r][c] < 0) {
                    found = r * 1000 + c;
                    break outer;
                }
                if (grid[r][c] == 0) {
                    continue outer;
                }
            }
        }
        return found;
    }

    // Смайлик 😀 занимает две кодовые единицы UTF-16: смещения после него не должны сдвигаться.
    public static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        } else if (value > high) {
            return high;
        }
        return value;
    }

    public static String describe(int number) {
        String size = number < 0 ? "отрицательное" : number == 0 ? "ноль" : "положительное";
        String parity = (number & 1) == 0 ? "чётное" : "нечётное";
        return size + ", " + parity;
    }

    // ------------------------------------------------------------------ console I/O

    /** Разбор команд вида "add <contact> <priority> <title>" из потока строк. */
    public static class CommandLine {
        private final Organizer organizer;
        private int errors;

        public CommandLine(Organizer organizer) {
            this.organizer = organizer;
        }

        public int run(BufferedReader in) throws IOException {
            int executed = 0;
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                try {
                    execute(line);
                    executed++;
                } catch (ValidationException e) {
                    errors++;
                    System.err.println("Ошибка проверки (" + e.getField() + "): " + e.getMessage());
                } catch (OrganizerException | NumberFormatException e) {
                    errors++;
                    System.err.println("Ошибка: " + e.getMessage());
                } finally {
                    System.out.flush();
                }
            }
            return executed;
        }

        private void execute(String line) throws OrganizerException {
            String[] parts = line.split("\\s+", 4);
            String command = parts[0].toLowerCase();
            switch (command) {
                case "add" -> {
                    if (parts.length < 4) {
                        throw new ValidationException("command", "add <контакт> <приоритет> <название>");
                    }
                    int id = organizer.addTask(Integer.parseInt(parts[1]), parts[3],
                            Priority.valueOf(parts[2].toUpperCase()), 30);
                    System.out.printf("Создана задача %d%n", id);
                }
                case "done" -> {
                    organizer.complete(Integer.parseInt(parts[1]));
                    System.out.println("Готово: " + parts[1]);
                }
                case "undo" -> {
                    String last = organizer.undo();
                    System.out.println(last == null ? "нечего отменять" : "отменено " + last);
                }
                default -> throw new ValidationException("command", "неизвестная команда " + command);
            }
        }

        public int getErrors() {
            return errors;
        }
    }

    public static int readNumbers(String text) {
        int total = 0;
        try (Scanner scanner = new Scanner(text)) {
            while (scanner.hasNextInt()) {
                total += scanner.nextInt();
            }
        }
        return total;
    }

    public static void printReport(Organizer organizer) {
        System.out.println("=== Отчёт ===");
        for (Map.Entry<Priority, List<Task>> entry : organizer.groupByPriority().entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue().size());
            for (Task task : entry.getValue()) {
                System.out.printf("  %-30s %s%n", task.getTitle(), task.getStatus().title());
            }
        }
        System.out.format("Открытых минут: %d%n", organizer.totalOpenMinutes());
    }

    // ------------------------------------------------------------------ exceptions in practice

    /** Ресурс, который сообщает о закрытии. */
    static class Journal implements AutoCloseable {
        private final List<String> lines = new ArrayList<>();
        private boolean closed;

        void write(String line) {
            if (closed) {
                throw new IllegalStateException("журнал закрыт");
            }
            lines.add(line);
        }

        @Override
        public void close() {
            closed = true;
        }

        int size() {
            return lines.size();
        }
    }

    public static int journalSize(String[] entries) {
        Journal journal = new Journal();
        try (journal; Journal backup = new Journal()) {
            for (String entry : entries) {
                journal.write(entry);
                backup.write(entry);
            }
            return journal.size();
        }
    }

    public static int parseOrDefault(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        } catch (NullPointerException e) {
            return -1;
        } finally {
            fallbackCounter++;
        }
    }

    private static int fallbackCounter;

    public static void rethrow(int mode) throws OrganizerException {
        try {
            if (mode == 1) {
                throw new ValidationException("mode", "режим 1 запрещён");
            }
            if (mode == 2) {
                throw new NotFoundException("Режим", mode);
            }
        } catch (ValidationException e) {
            throw new OrganizerException("обёрнуто", e.getCode(), e);
        } catch (RuntimeException e) {
            System.err.println("runtime: " + e.getMessage());
            throw e;
        }
    }

    public static int safeDivide(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ lambdas, inner and local classes

    public interface Formatter {
        String format(Task task);
    }

    public Formatter shortFormatter() {
        return new Formatter() {
            private int calls;

            @Override
            public String format(Task task) {
                calls++;
                return calls + ". " + task.getTitle();
            }
        };
    }

    public static List<String> titles(List<Task> list, Function<Task, String> mapper) {
        List<String> result = new ArrayList<>();
        list.forEach(t -> result.add(mapper.apply(t)));
        return result;
    }

    public static String longestTitle(List<Task> list) {
        class Best {
            String value = "";

            void offer(String candidate) {
                if (candidate.length() > value.length()) {
                    value = candidate;
                }
            }
        }
        Best best = new Best();
        for (Task task : list) {
            best.offer(task.getTitle());
        }
        return best.value;
    }

    public class Counter {
        private int count;

        public int next() {
            return ++count + history.size();
        }
    }

    public static Comparator<Task> byTitle() {
        return (a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle());
    }

    public static String banner(String user) {
        var text = """
                Добро пожаловать, %s!
                Сегодня хороший день для задач.
                """.formatted(user);
        return text.strip();
    }

    public static String kind(Object value) {
        if (value instanceof Integer i && i > 100) {
            return "большое число";
        } else if (value instanceof String s) {
            return "строка длиной " + s.length();
        }
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    public static int weekdayLength(int day) {
        return switch (day) {
            case 1, 2, 3, 4, 5 -> {
                int base = 8;
                yield base + day % 2;
            }
            case 6, 7 -> 0;
            default -> throw new IllegalArgumentException("day " + day);
        };
    }

    public static int sumOf(int... numbers) {
        int sum = 0;
        for (int number : numbers) {
            sum += number;
        }
        return sum;
    }

    public static <T extends Comparable<T>> T maxOf(List<T> values) {
        T best = null;
        for (T value : values) {
            if (best == null || value.compareTo(best) > 0) {
                best = value;
            }
        }
        return best;
    }

    public static int countWhere(List<Task> list, Predicate<Task> test) {
        int count = 0;
        for (Task task : list) {
            if (test.test(task)) {
                count++;
            }
        }
        return count;
    }

    public static String joinTags(Collection<String> tags) {
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(tag);
        }
        return sb.toString();
    }

    public static Queue<Integer> countdown(int from) {
        Queue<Integer> queue = new LinkedList<>();
        for (int i = from; i > 0; i--) {
            queue.offer(i);
        }
        return queue;
    }

    public static synchronized void touch() {
        fallbackCounter++;
    }

    public static int fallbackCount() {
        return fallbackCounter;
    }

    public static void main(String[] args) throws Exception {
        Organizer organizer = new Organizer();
        int anna = organizer.addContact("Анна Петрова", "anna@example.org", "Москва");
        int boris = organizer.addContact("Борис", "boris@example.org", "Казань");
        organizer.addTask(anna, "Подготовить отчёт", Priority.HIGH, 90);
        organizer.addTask(boris, "Позвонить клиенту", Priority.NORMAL, 15);
        organizer.addNote("Идея", "Сделать визуальный редактор", "идеи", "ui");

        String script = "add " + anna + " urgent Оплатить счёт\n# комментарий\ndone 3\nundo\nfoo\n";
        CommandLine cli = new CommandLine(organizer);
        int executed = cli.run(new BufferedReader(new StringReader(script)));
        System.out.println("Выполнено команд: " + executed + ", ошибок: " + cli.getErrors());
        printReport(organizer);
        System.out.println(banner("Анна"));
    }
}
