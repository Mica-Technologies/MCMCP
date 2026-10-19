package com.micatechnologies.minecraft.mcmcp.jobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The small language a {@code server_run_test} script is written in: {@code ${...}} templates, the
 * expressions inside them, paths into tool results, and the conditions a wait waits for (#111).
 *
 * <p>A building test is a loop of "do this, wait until the world says that, note how long". The
 * doing and the reading are ordinary tool calls; what this adds is only what glues them: putting a
 * variable into an argument ({@code "y": "${c.y + 1}"}), and asking a result a question
 * ({@code {"path": "blocks[*].y", "anyIn": [65, 66]}}).
 *
 * <ul>
 *   <li>A string that is exactly one {@code ${expr}} becomes the expression's value with its type: a
 *       number stays a number, an array an array. Any other string has each {@code ${expr}} replaced
 *       by its text.</li>
 *   <li>Expressions: numbers, {@code 'strings'}, {@code true false null}, paths ({@code c.y},
 *       {@code ctl.blocks[0].y}, {@code ctl.blocks[*].y}), {@code + - * / %}, comparisons,
 *       {@code && || !}, parentheses, and {@code len abs min max round floor}.</li>
 *   <li>Paths take {@code .name}, {@code [n]} and {@code [*]}; a {@code [*]} anywhere makes the result
 *       the list of every match, flattened.</li>
 * </ul>
 *
 * <p>Pure: Gson only, unit-tested directly. Errors are {@link IllegalArgumentException}s whose message
 * is meant for the script's author.
 */
public final class TestExpressions {

    private TestExpressions() {
    }

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    /** A copy of {@code template} with every {@code ${expr}} in its strings evaluated. */
    public static JsonElement resolve(JsonElement template, Map<String, JsonElement> scope) {
        if (template == null || template.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (template.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : template.getAsJsonObject().entrySet()) {
                copy.add(entry.getKey(), resolve(entry.getValue(), scope));
            }
            return copy;
        }
        if (template.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement element : template.getAsJsonArray()) {
                copy.add(resolve(element, scope));
            }
            return copy;
        }
        JsonPrimitive primitive = template.getAsJsonPrimitive();
        if (!primitive.isString()) {
            return primitive;
        }
        String text = primitive.getAsString();
        int open = text.indexOf("${");
        if (open < 0) {
            return primitive;
        }
        int close = matchingBrace(text, open + 2);
        if (open == 0 && close == text.length() - 1) {
            return evaluate(text.substring(2, close), scope);
        }
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (open >= 0) {
            close = matchingBrace(text, open + 2);
            out.append(text, at, open).append(display(evaluate(text.substring(open + 2, close), scope)));
            at = close + 1;
            open = text.indexOf("${", at);
        }
        return new JsonPrimitive(out.append(text.substring(at)).toString());
    }

    private static int matchingBrace(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        throw new IllegalArgumentException("Unclosed ${ in \"" + text + "\".");
    }

    /** A value as it reads inside a longer string: numbers without a needless ".0". */
    public static String display(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                double number = primitive.getAsDouble();
                return number == Math.rint(number) && Math.abs(number) < 1e15
                    ? Long.toString((long) number) : Double.toString(number);
            }
            return primitive.getAsString();
        }
        return value.toString();
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    /**
     * What {@code path} names in {@code root}: one value, or with a {@code [*]} the list of every
     * match. A step that does not exist is null, not an error: "no blocks found" is an answer.
     */
    public static JsonElement path(JsonElement root, String path) {
        List<JsonElement> current = new ArrayList<>();
        current.add(root);
        boolean many = false;
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
                continue;
            }
            List<JsonElement> next = new ArrayList<>();
            if (c == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed [ in path '" + path + "'.");
                }
                String index = path.substring(i + 1, end).trim();
                i = end + 1;
                for (JsonElement raw : current) {
                    JsonElement element = listOf(raw);
                    if (element == null || !element.isJsonArray()) {
                        continue;
                    }
                    JsonArray array = element.getAsJsonArray();
                    if ("*".equals(index)) {
                        many = true;
                        for (JsonElement item : array) {
                            next.add(item);
                        }
                    }
                    else {
                        int n = Integer.parseInt(index);
                        if (n < 0) {
                            n += array.size();
                        }
                        if (n >= 0 && n < array.size()) {
                            next.add(array.get(n));
                        }
                    }
                }
            }
            else {
                int end = i;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                String name = path.substring(i, end);
                i = end;
                for (JsonElement element : current) {
                    if (element != null && element.isJsonObject() && element.getAsJsonObject().has(name)) {
                        next.add(element.getAsJsonObject().get(name));
                    }
                }
            }
            current = next;
        }
        if (many) {
            JsonArray all = new JsonArray();
            for (JsonElement element : current) {
                all.add(element);
            }
            return all;
        }
        return current.isEmpty() || current.get(0) == null ? JsonNull.INSTANCE : current.get(0);
    }

    /**
     * A table ({columns, rows}) as the list of objects it stands for; anything else as it is. Big
     * lists in tool results are tables, and a script reads them the same way either shape.
     */
    public static JsonElement listOf(JsonElement value) {
        return com.micatechnologies.minecraft.mcmcp.json.JsonTables.isTable(value)
            ? com.micatechnologies.minecraft.mcmcp.json.JsonTables.rows(value) : value;
    }

    // ------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------

    /**
     * Whether a result passes a condition: a {@code ${...}} string, with the result as
     * {@code result}, or an object naming a {@code path} into the result and one test of it.
     */
    public static boolean test(JsonElement condition, JsonElement result, Map<String, JsonElement> scope) {
        Map<String, JsonElement> withResult = new java.util.HashMap<>(scope);
        withResult.put("result", result);
        if (condition.isJsonPrimitive()) {
            return truthy(resolve(condition, withResult));
        }
        if (!condition.isJsonObject()) {
            throw new IllegalArgumentException("A condition is a \"${...}\" string or an object with a 'path'.");
        }
        JsonObject spec = condition.getAsJsonObject();
        String pathText = spec.has("path") ? spec.get("path").getAsString() : "";
        JsonElement value = listOf(pathText.isEmpty() ? result : path(result, pathText));
        List<JsonElement> values = new ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                values.add(element);
            }
        }
        else if (!value.isJsonNull()) {
            values.add(value);
        }
        if (spec.has("where")) {
            JsonObject where = resolve(spec.get("where"), withResult).getAsJsonObject();
            List<JsonElement> kept = new ArrayList<>();
            for (JsonElement element : values) {
                boolean all = true;
                for (Map.Entry<String, JsonElement> entry : where.entrySet()) {
                    all &= looselyEqual(path(element, entry.getKey()), entry.getValue());
                }
                if (all) {
                    kept.add(element);
                }
            }
            values = kept;
        }
        if (spec.has("anyIn")) {
            JsonArray wanted = resolve(spec.get("anyIn"), withResult).getAsJsonArray();
            for (JsonElement element : values) {
                for (JsonElement candidate : wanted) {
                    if (looselyEqual(element, candidate)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (spec.has("equals")) {
            JsonElement wanted = resolve(spec.get("equals"), withResult);
            return !values.isEmpty() && looselyEqual(value.isJsonArray() ? value : values.get(0), wanted);
        }
        if (spec.has("atLeast")) {
            return values.size() >= resolve(spec.get("atLeast"), withResult).getAsInt();
        }
        if (spec.has("none")) {
            return values.isEmpty() == truthy(resolve(spec.get("none"), withResult));
        }
        // A path and nothing else: something matched.
        return !values.isEmpty() && (values.size() > 1 || truthy(values.get(0)));
    }

    /**
     * Equality for values that came through NBT and JSON: 1, 1.0, "1" and true are the same when
     * one side is a boolean or both are numbers, since a byte flag reads as 1b and a script writes
     * true.
     */
    public static boolean looselyEqual(JsonElement a, JsonElement b) {
        if (a == null || a.isJsonNull() || b == null || b.isJsonNull()) {
            return (a == null || a.isJsonNull()) && (b == null || b.isJsonNull());
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive x = a.getAsJsonPrimitive();
            JsonPrimitive y = b.getAsJsonPrimitive();
            if (x.isBoolean() || y.isBoolean()) {
                return truthy(x) == truthy(y);
            }
            Double dx = number(x);
            Double dy = number(y);
            if (dx != null && dy != null) {
                return Math.abs(dx - dy) < 1e-9;
            }
            return x.getAsString().equalsIgnoreCase(y.getAsString());
        }
        return a.equals(b);
    }

    public static boolean truthy(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (value.isJsonArray()) {
            return value.getAsJsonArray().size() > 0;
        }
        if (value.isJsonObject()) {
            return true;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble() != 0.0D;
        }
        String text = primitive.getAsString();
        return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"0".equals(text);
    }

    private static Double number(JsonPrimitive primitive) {
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        try {
            return Double.parseDouble(primitive.getAsString().replaceAll("[bBsSlLfFdD]$", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    public static JsonElement evaluate(String expression, Map<String, JsonElement> scope) {
        Parser parser = new Parser(expression, scope);
        JsonElement value = parser.or();
        parser.skipSpace();
        if (parser.at < expression.length()) {
            throw new IllegalArgumentException("Unexpected '" + expression.substring(parser.at) + "' in ${"
                + expression + "}.");
        }
        return value;
    }

    private static final class Parser {

        final String text;
        final Map<String, JsonElement> scope;
        int at;

        Parser(String text, Map<String, JsonElement> scope) {
            this.text = text;
            this.scope = scope;
        }

        void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        boolean eat(String token) {
            skipSpace();
            if (text.startsWith(token, at)) {
                at += token.length();
                return true;
            }
            return false;
        }

        JsonElement or() {
            JsonElement left = and();
            while (eat("||")) {
                JsonElement right = and();
                left = new JsonPrimitive(truthy(left) || truthy(right));
            }
            return left;
        }

        JsonElement and() {
            JsonElement left = comparison();
            while (eat("&&")) {
                JsonElement right = comparison();
                left = new JsonPrimitive(truthy(left) && truthy(right));
            }
            return left;
        }

        JsonElement comparison() {
            JsonElement left = sum();
            for (String op : new String[] {"==", "!=", "<=", ">=", "<", ">"}) {
                if (eat(op)) {
                    JsonElement right = sum();
                    switch (op) {
                        case "==":
                            return new JsonPrimitive(looselyEqual(left, right));
                        case "!=":
                            return new JsonPrimitive(!looselyEqual(left, right));
                        default:
                            double a = numeric(left);
                            double b = numeric(right);
                            return new JsonPrimitive("<".equals(op) ? a < b : ">".equals(op) ? a > b
                                : "<=".equals(op) ? a <= b : a >= b);
                    }
                }
            }
            return left;
        }

        JsonElement sum() {
            JsonElement left = term();
            while (true) {
                if (eat("+")) {
                    JsonElement right = term();
                    left = isNumber(left) && isNumber(right) ? new JsonPrimitive(numeric(left) + numeric(right))
                        : new JsonPrimitive(display(left) + display(right));
                }
                else if (eat("-")) {
                    left = new JsonPrimitive(numeric(left) - numeric(term()));
                }
                else {
                    return left;
                }
            }
        }

        JsonElement term() {
            JsonElement left = unary();
            while (true) {
                if (eat("*")) {
                    left = new JsonPrimitive(numeric(left) * numeric(unary()));
                }
                else if (eat("/")) {
                    left = new JsonPrimitive(numeric(left) / numeric(unary()));
                }
                else if (eat("%")) {
                    left = new JsonPrimitive(numeric(left) % numeric(unary()));
                }
                else {
                    return left;
                }
            }
        }

        JsonElement unary() {
            if (eat("!")) {
                return new JsonPrimitive(!truthy(unary()));
            }
            if (eat("-")) {
                return new JsonPrimitive(-numeric(unary()));
            }
            return primary();
        }

        /** A value and what follows it: {@code .name}, {@code [index]}, {@code [*]}, any number of them. */
        JsonElement primary() {
            JsonElement value = atom();
            boolean many = false;
            while (true) {
                skipSpace();
                if (at + 1 < text.length() && text.charAt(at) == '.'
                    && (Character.isLetter(text.charAt(at + 1)) || text.charAt(at + 1) == '_')) {
                    at++;
                    int start = at;
                    while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                        at++;
                    }
                    final String member = text.substring(start, at);
                    value = step(value, many, element -> element != null && element.isJsonObject()
                        ? element.getAsJsonObject().get(member) : null);
                }
                else if (at < text.length() && text.charAt(at) == '[') {
                    at++;
                    int open = at;
                    if (!(eat("*") && eat("]"))) {
                        at = open;
                    }
                    else {
                        JsonArray all = new JsonArray();
                        for (JsonElement each : many ? elements(value) : java.util.Collections.singletonList(value)) {
                            JsonElement element = listOf(each);
                            if (element != null && element.isJsonArray()) {
                                for (JsonElement item : element.getAsJsonArray()) {
                                    all.add(item);
                                }
                            }
                        }
                        value = all;
                        many = true;
                        continue;
                    }
                    final JsonElement key = or();
                    if (!eat("]")) {
                        throw new IllegalArgumentException("Missing ] in ${" + text + "}.");
                    }
                    value = step(value, many, element -> index(element, key));
                }
                else {
                    return value;
                }
            }
        }

        private List<JsonElement> elements(JsonElement value) {
            List<JsonElement> list = new ArrayList<>();
            value = listOf(value);
            if (value != null && value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    list.add(item);
                }
            }
            return list;
        }

        /** One postfix step, applied to each match after a [*], to the value otherwise. */
        private JsonElement step(JsonElement value, boolean many, java.util.function.Function<JsonElement, JsonElement> op) {
            if (!many) {
                JsonElement next = op.apply(value);
                return next == null ? JsonNull.INSTANCE : next;
            }
            JsonArray mapped = new JsonArray();
            for (JsonElement element : elements(value)) {
                JsonElement next = op.apply(element);
                if (next != null && !next.isJsonNull()) {
                    mapped.add(next);
                }
            }
            return mapped;
        }

        private JsonElement index(JsonElement element, JsonElement key) {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            if (key.isJsonPrimitive() && key.getAsJsonPrimitive().isNumber()) {
                element = listOf(element);
            }
            if (element.isJsonArray() && key.isJsonPrimitive() && key.getAsJsonPrimitive().isNumber()) {
                JsonArray array = element.getAsJsonArray();
                int n = key.getAsInt();
                if (n < 0) {
                    n += array.size();
                }
                return n >= 0 && n < array.size() ? array.get(n) : null;
            }
            if (element.isJsonObject()) {
                return element.getAsJsonObject().get(display(key));
            }
            return null;
        }

        JsonElement atom() {
            skipSpace();
            if (at >= text.length()) {
                throw new IllegalArgumentException("${" + text + "} ends where a value was expected.");
            }
            char c = text.charAt(at);
            if (eat("(")) {
                JsonElement inner = or();
                if (!eat(")")) {
                    throw new IllegalArgumentException("Missing ) in ${" + text + "}.");
                }
                return inner;
            }
            // A list literal: [a, b, c].
            if (eat("[")) {
                JsonArray list = new JsonArray();
                if (!eat("]")) {
                    do {
                        list.add(or());
                    } while (eat(","));
                    if (!eat("]")) {
                        throw new IllegalArgumentException("Missing ] in a list in ${" + text + "}.");
                    }
                }
                return list;
            }
            // A map literal: {n: [0, 1], 'south': 3}. Lookup tables, such as facing to offset.
            if (eat("{")) {
                JsonObject map = new JsonObject();
                if (!eat("}")) {
                    do {
                        skipSpace();
                        String key;
                        if (at < text.length() && text.charAt(at) == '\'') {
                            key = atom().getAsString();
                        }
                        else {
                            int start = at;
                            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                                at++;
                            }
                            key = text.substring(start, at);
                        }
                        if (key.isEmpty() || !eat(":")) {
                            throw new IllegalArgumentException("A map is {key: value, ...} in ${" + text + "}.");
                        }
                        map.add(key, or());
                    } while (eat(","));
                    if (!eat("}")) {
                        throw new IllegalArgumentException("Missing } in a map in ${" + text + "}.");
                    }
                }
                return map;
            }
            if (c == '\'') {
                int end = text.indexOf('\'', at + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed ' in ${" + text + "}.");
                }
                String literal = text.substring(at + 1, end);
                at = end + 1;
                return new JsonPrimitive(literal);
            }
            if (Character.isDigit(c)) {
                int start = at;
                while (at < text.length() && (Character.isDigit(text.charAt(at))
                    || text.charAt(at) == '.' && at + 1 < text.length() && Character.isDigit(text.charAt(at + 1)))) {
                    at++;
                }
                double value = Double.parseDouble(text.substring(start, at));
                return value == Math.rint(value) && Math.abs(value) < 1e15
                    ? new JsonPrimitive((long) value) : new JsonPrimitive(value);
            }
            if (Character.isLetter(c) || c == '_') {
                int start = at;
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                    at++;
                }
                String name = text.substring(start, at);
                if (eat("(")) {
                    List<JsonElement> args = new ArrayList<>();
                    if (!eat(")")) {
                        do {
                            args.add(or());
                        } while (eat(","));
                        if (!eat(")")) {
                            throw new IllegalArgumentException("Missing ) after " + name + "( in ${" + text + "}.");
                        }
                    }
                    return call(name, args);
                }
                if ("true".equals(name) || "false".equals(name)) {
                    return new JsonPrimitive(Boolean.parseBoolean(name));
                }
                if ("null".equals(name)) {
                    return JsonNull.INSTANCE;
                }
                if (!scope.containsKey(name)) {
                    throw new IllegalArgumentException("No variable '" + name + "' in ${" + text + "}. Known: "
                        + scope.keySet() + ".");
                }
                return scope.get(name);
            }
            throw new IllegalArgumentException("Unexpected '" + c + "' in ${" + text + "}.");
        }

        JsonElement call(String name, List<JsonElement> args) {
            switch (name) {
                case "len": {
                    JsonElement value = listOf(args.isEmpty() ? JsonNull.INSTANCE : args.get(0));
                    return new JsonPrimitive(value.isJsonArray() ? value.getAsJsonArray().size()
                        : value.isJsonObject() ? value.getAsJsonObject().size()
                        : value.isJsonNull() ? 0 : display(value).length());
                }
                case "abs":
                    return new JsonPrimitive(Math.abs(numeric(args.get(0))));
                case "floor":
                    return new JsonPrimitive((long) Math.floor(numeric(args.get(0))));
                case "round": {
                    int places = args.size() > 1 ? (int) numeric(args.get(1)) : 0;
                    double scale = Math.pow(10, places);
                    return new JsonPrimitive(Math.round(numeric(args.get(0)) * scale) / scale);
                }
                case "min":
                case "max": {
                    List<JsonElement> values = new ArrayList<>();
                    for (JsonElement arg : args) {
                        if (arg.isJsonArray()) {
                            for (JsonElement item : arg.getAsJsonArray()) {
                                values.add(item);
                            }
                        }
                        else {
                            values.add(arg);
                        }
                    }
                    if (values.isEmpty()) {
                        return JsonNull.INSTANCE;
                    }
                    double best = numeric(values.get(0));
                    for (JsonElement value : values) {
                        best = "min".equals(name) ? Math.min(best, numeric(value)) : Math.max(best, numeric(value));
                    }
                    return new JsonPrimitive(best);
                }
                case "sort":
                case "unique": {
                    List<JsonElement> values = elements(args.isEmpty() ? null : args.get(0));
                    if ("unique".equals(name)) {
                        List<JsonElement> kept = new ArrayList<>();
                        for (JsonElement value : values) {
                            boolean seen = false;
                            for (JsonElement earlier : kept) {
                                seen |= looselyEqual(earlier, value);
                            }
                            if (!seen) {
                                kept.add(value);
                            }
                        }
                        values = kept;
                    }
                    else {
                        values.sort((a, b) -> isNumber(a) && isNumber(b) ? Double.compare(numeric(a), numeric(b))
                            : display(a).compareTo(display(b)));
                    }
                    JsonArray out = new JsonArray();
                    values.forEach(out::add);
                    return out;
                }
                case "slice": {
                    // As Python's list[from:to]: negative counts from the end, 'to' may be left out.
                    List<JsonElement> values = elements(args.get(0));
                    int size = values.size();
                    int from = args.size() > 1 ? (int) numeric(args.get(1)) : 0;
                    int to = args.size() > 2 ? (int) numeric(args.get(2)) : size;
                    from = Math.max(0, Math.min(size, from < 0 ? from + size : from));
                    to = Math.max(from, Math.min(size, to < 0 ? to + size : to));
                    JsonArray out = new JsonArray();
                    values.subList(from, to).forEach(out::add);
                    return out;
                }
                case "concat": {
                    JsonArray out = new JsonArray();
                    for (JsonElement arg : args) {
                        if (arg.isJsonArray()) {
                            arg.getAsJsonArray().forEach(out::add);
                        }
                        else {
                            out.add(arg);
                        }
                    }
                    return out;
                }
                case "minBy":
                case "maxBy": {
                    // minBy(list, 'distance'): the item whose value at that path is smallest.
                    String key = args.size() > 1 ? display(args.get(1)) : "";
                    JsonElement best = JsonNull.INSTANCE;
                    double bestValue = 0.0D;
                    for (JsonElement item : elements(args.get(0))) {
                        JsonElement at = key.isEmpty() ? item : path(item, key);
                        if (!isNumber(at)) {
                            continue;
                        }
                        double value = numeric(at);
                        if (best.isJsonNull() || ("minBy".equals(name) ? value < bestValue : value > bestValue)) {
                            best = item;
                            bestValue = value;
                        }
                    }
                    return best;
                }
                case "meta": {
                    // The metadata of an id as reads report it, "mod:block:5" (0 when there is none).
                    String id = display(args.get(0));
                    int colon = id.lastIndexOf(':');
                    if (colon > 0 && id.indexOf(':') != colon) {
                        try {
                            return new JsonPrimitive(Integer.parseInt(id.substring(colon + 1)));
                        } catch (NumberFormatException e) {
                            return new JsonPrimitive(0);
                        }
                    }
                    return new JsonPrimitive(0);
                }
                default:
                    throw new IllegalArgumentException("No function '" + name + "'; there are len, abs, floor, "
                        + "round, min, max, sort, unique, slice, concat, minBy, maxBy and meta.");
            }
        }

        boolean isNumber(JsonElement value) {
            return value != null && value.isJsonPrimitive() && number(value.getAsJsonPrimitive()) != null
                && !value.getAsJsonPrimitive().isBoolean();
        }

        double numeric(JsonElement value) {
            if (value == null || value.isJsonNull()) {
                throw new IllegalArgumentException("A number was needed in ${" + text + "}, and a value was "
                    + "missing (null).");
            }
            if (value.isJsonPrimitive()) {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    return primitive.getAsBoolean() ? 1.0D : 0.0D;
                }
                Double parsed = number(primitive);
                if (parsed != null) {
                    return parsed;
                }
            }
            throw new IllegalArgumentException("A number was needed in ${" + text + "}, not " + value + ".");
        }
    }
}
