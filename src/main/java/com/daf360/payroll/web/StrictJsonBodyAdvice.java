package com.daf360.payroll.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rejects unknown top-level fields for {@link StrictJson} records (A4): the body is read once,
 * its field names compared with the record's components, and replayed to Jackson unchanged.
 * An unknown field throws {@link IllegalArgumentException}, i.e. a 400 problem detail.
 */
@ControllerAdvice
public class StrictJsonBodyAdvice extends RequestBodyAdviceAdapter {

    private final ObjectMapper objectMapper;

    public StrictJsonBodyAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType instanceof Class<?> c && c.isRecord() && c.isAnnotationPresent(StrictJson.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType, Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = inputMessage.getBody().readAllBytes();
        checkFields(body, (Class<?>) targetType);
        HttpHeaders headers = inputMessage.getHeaders();
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }

    /** Visible for tests. */
    void checkFields(byte[] body, Class<?> type) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return; // malformed JSON: Jackson reports it next (400 via HttpMessageNotReadableException)
        }
        if (root == null || !root.isObject()) return;
        Set<String> known = Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        List<String> unknown = new ArrayList<>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!known.contains(f)) unknown.add(f);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Champ(s) inconnu(s) dans la requête : "
                    + String.join(", ", unknown) + ". Champs acceptés : "
                    + known.stream().sorted().collect(Collectors.joining(", ")) + ".");
        }
    }
}
