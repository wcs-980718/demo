package com.yiwei.midplat.evaluation.evaluator;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiwei.midplat.evaluation.casecenter.EvaluatorType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stateless deterministic evaluators using strict, body-free JSON parsing and outcomes. */
public final class EvaluationEvaluatorRegistry {
    private static final Pattern JSON_PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern FIRST_NUMBER = Pattern.compile(
            "(?<![\\p{Alnum}_.])[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?(?![\\p{Alnum}_.])");

    private final ObjectMapper objectMapper;
    private final Map<EvaluatorType, DeterministicEvaluator> evaluators;

    public EvaluationEvaluatorRegistry() {
        this(new ObjectMapper());
    }

    EvaluationEvaluatorRegistry(ObjectMapper sourceObjectMapper) {
        objectMapper = sourceObjectMapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        EnumMap<EvaluatorType, DeterministicEvaluator> registered = new EnumMap<>(EvaluatorType.class);
        registered.put(EvaluatorType.EXACT, this::evaluateExact);
        registered.put(EvaluatorType.CONTAINS_ALL, this::evaluateContainsAll);
        registered.put(EvaluatorType.JSON_VALID, this::evaluateJsonValid);
        registered.put(EvaluatorType.NUMERIC_RANGE, this::evaluateNumericRange);
        registered.put(EvaluatorType.FORBIDDEN_TERMS, this::evaluateForbiddenTerms);
        registered.put(EvaluatorType.MANUAL, this::evaluateManual);
        evaluators = Map.copyOf(registered);
    }

    public EvaluationOutcome evaluate(EvaluatorType type, String expectedJson, String actualOutput) {
        if (type == null) return EvaluationOutcome.invalidConfiguration();
        DeterministicEvaluator evaluator = evaluators.get(type);
        if (evaluator == null) return EvaluationOutcome.invalidConfiguration();
        try {
            return evaluator.evaluate(expectedJson, actualOutput);
        } catch (InvalidConfigurationException exception) {
            return EvaluationOutcome.invalidConfiguration();
        } catch (RuntimeException exception) {
            return EvaluationOutcome.invalidConfiguration();
        }
    }

    private EvaluationOutcome evaluateExact(String expectedJson, String actualOutput) {
        JsonNode expected = expectedObject(expectedJson, "value");
        String required = requiredText(expected, "value");
        if (actualOutput == null) return missingActual();
        boolean matched = normalizeWhitespace(required).equals(normalizeWhitespace(actualOutput));
        return EvaluationOutcome.automated(matched, matched ? EvaluationReason.EXACT_MATCH : EvaluationReason.EXACT_MISMATCH);
    }

    private EvaluationOutcome evaluateContainsAll(String expectedJson, String actualOutput) {
        List<String> required = requiredStrings(expectedObject(expectedJson, "containsAll"), "containsAll", false);
        if (actualOutput == null) return missingActual();
        int matchedCount = 0;
        for (String fragment : required) if (actualOutput.contains(fragment)) matchedCount++;
        boolean passed = matchedCount == required.size();
        return EvaluationOutcome.automated(passed, passed ? EvaluationReason.CONTAINS_ALL_MATCH : EvaluationReason.CONTAINS_ALL_MISSING,
                required.size(), matchedCount);
    }

    private EvaluationOutcome evaluateJsonValid(String expectedJson, String actualOutput) {
        List<String> fields = requiredStrings(expectedObject(expectedJson, "requiredFields"), "requiredFields", true);
        if (actualOutput == null) return missingActual();
        JsonNode actual = actualJson(actualOutput);
        if (actual == null) return EvaluationOutcome.automated(false, EvaluationReason.JSON_INVALID);
        if (fields.isEmpty()) return EvaluationOutcome.automated(true, EvaluationReason.JSON_VALID, 0, 0);
        if (!actual.isObject()) return EvaluationOutcome.automated(false, EvaluationReason.JSON_ROOT_NOT_OBJECT);
        int matchedCount = 0;
        for (String field : fields) if (actual.hasNonNull(field)) matchedCount++;
        if (matchedCount != fields.size()) {
            return EvaluationOutcome.automated(false, EvaluationReason.JSON_REQUIRED_FIELDS_MISSING, fields.size(), matchedCount);
        }
        return EvaluationOutcome.automated(true, EvaluationReason.JSON_VALID, fields.size(), 0);
    }

    private EvaluationOutcome evaluateNumericRange(String expectedJson, String actualOutput) {
        JsonNode expected = expectedObject(expectedJson, "min", "max", "jsonPath");
        BigDecimal minimum = requiredRootNumber(expectedJson, "min");
        BigDecimal maximum = requiredRootNumber(expectedJson, "max");
        if (minimum.compareTo(maximum) > 0) throw new InvalidConfigurationException();
        String jsonPath = optionalJsonPath(expected);
        if (actualOutput == null) return missingActual();
        BigDecimal actual = jsonPath == null ? firstNumber(actualOutput) : numberAtJsonPath(actualOutput, jsonPath);
        if (actual == null) return EvaluationOutcome.automated(false, EvaluationReason.NUMERIC_NOT_FOUND);
        boolean passed = actual.compareTo(minimum) >= 0 && actual.compareTo(maximum) <= 0;
        return EvaluationOutcome.automated(passed, passed ? EvaluationReason.NUMERIC_IN_RANGE : EvaluationReason.NUMERIC_OUT_OF_RANGE);
    }

    private EvaluationOutcome evaluateForbiddenTerms(String expectedJson, String actualOutput) {
        List<String> terms = requiredStrings(expectedObject(expectedJson, "forbiddenTerms"), "forbiddenTerms", false);
        if (actualOutput == null) return missingActual();
        int hitCount = 0;
        for (String term : terms) if (actualOutput.contains(term)) hitCount++;
        boolean passed = hitCount == 0;
        return EvaluationOutcome.automated(passed, passed ? EvaluationReason.FORBIDDEN_TERMS_CLEAR : EvaluationReason.FORBIDDEN_TERMS_FOUND,
                terms.size(), hitCount);
    }

    private EvaluationOutcome evaluateManual(String expectedJson, String actualOutput) {
        expectedObject(expectedJson);
        return EvaluationOutcome.manual();
    }

    private JsonNode expectedObject(String expectedJson, String... allowedFields) {
        JsonNode node = strictJson(expectedJson);
        if (node == null || !node.isObject()) throw new InvalidConfigurationException();
        Set<String> allowed = Set.of(allowedFields);
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new InvalidConfigurationException();
        });
        return node;
    }

    private JsonNode strictJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new InvalidConfigurationException();
        }
    }

    private JsonNode actualJson(String actualOutput) {
        try {
            return objectMapper.readTree(actualOutput);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual()) throw new InvalidConfigurationException();
        return node.textValue();
    }

    private static List<String> requiredStrings(JsonNode object, String field, boolean allowEmpty) {
        JsonNode node = object.get(field);
        if (node == null || !node.isArray() || (!allowEmpty && node.isEmpty())) throw new InvalidConfigurationException();
        List<String> values = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual() || unicodeBlank(item.textValue()) || !distinct.add(item.textValue())) {
                throw new InvalidConfigurationException();
            }
            values.add(item.textValue());
        }
        return List.copyOf(values);
    }

    private BigDecimal requiredRootNumber(String expectedJson, String field) {
        try (JsonParser parser = objectMapper.getFactory().createParser(expectedJson)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new InvalidConfigurationException();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new InvalidConfigurationException();
                String candidate = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (field.equals(candidate)) {
                    if (!valueToken.isNumeric()) throw new InvalidConfigurationException();
                    return new BigDecimal(parser.getText());
                }
                parser.skipChildren();
            }
            throw new InvalidConfigurationException();
        } catch (InvalidConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidConfigurationException();
        }
    }

    private static String optionalJsonPath(JsonNode object) {
        JsonNode node = object.get("jsonPath");
        if (node == null) return null;
        if (!node.isTextual() || !JSON_PATH.matcher(node.textValue()).matches()) throw new InvalidConfigurationException();
        return node.textValue();
    }

    private BigDecimal numberAtJsonPath(String actualOutput, String jsonPath) {
        if (actualJson(actualOutput) == null) return null;
        try (JsonParser parser = objectMapper.getFactory().createParser(actualOutput)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            return numberInObject(parser, jsonPath.split("\\."), 0);
        } catch (RuntimeException exception) {
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static BigDecimal numberInObject(JsonParser parser, String[] segments, int index) throws java.io.IOException {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) return null;
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if (!segments[index].equals(fieldName)) {
                parser.skipChildren();
                continue;
            }
            if (index == segments.length - 1) {
                return valueToken.isNumeric() ? new BigDecimal(parser.getText()) : null;
            }
            return valueToken == JsonToken.START_OBJECT ? numberInObject(parser, segments, index + 1) : null;
        }
        return null;
    }

    private static BigDecimal firstNumber(String actualOutput) {
        Matcher matcher = FIRST_NUMBER.matcher(actualOutput);
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static EvaluationOutcome missingActual() {
        return EvaluationOutcome.automated(false, EvaluationReason.ACTUAL_OUTPUT_MISSING);
    }

    private static boolean unicodeBlank(String value) {
        if (value.isEmpty()) return true;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (!(Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) return false;
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static String normalizeWhitespace(String value) {
        StringBuilder normalized = new StringBuilder();
        boolean previousWhitespace = true;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            boolean whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
            if (whitespace) {
                if (!previousWhitespace) normalized.append(' ');
            } else {
                normalized.appendCodePoint(codePoint);
            }
            previousWhitespace = whitespace;
            index += Character.charCount(codePoint);
        }
        if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == ' ') normalized.setLength(normalized.length() - 1);
        return normalized.toString();
    }

    private static final class InvalidConfigurationException extends RuntimeException {
        private InvalidConfigurationException() {
            super(null, null, false, false);
        }
    }
}
