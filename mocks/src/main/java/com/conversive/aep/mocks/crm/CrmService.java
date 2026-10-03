package com.conversive.aep.mocks.crm;

import com.conversive.aep.mocks.admin.MockControls;
import com.conversive.aep.mocks.admin.Resettable;
import com.conversive.aep.mocks.support.MockHttpException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Non-idempotent CRM. The REST {@code POST /crm/contacts} always creates (duplicates are the point). The MCP
 * {@code crm.upsert} is a real upsert keyed by {@code external_ref}: it stores the caller's effect key (the
 * {@code Idempotency-Key} header) on the record and reports whether it {@code created} the record or updated a
 * pre-existing one, so a caller can scope lookups and deletes to what its own effect wrote.
 */
@Service
public class CrmService implements Resettable {

    public static final String UPSERT_ROUTE = "crm.upsert";
    public static final String GET_ROUTE = "crm.get";
    public static final String DELETE_ROUTE = "crm.delete";

    public record ContactRequest(@JsonProperty("external_ref") String externalRef, String name, String email,
                                 String label) {
    }

    private record Contact(String contactId, ContactRequest request, String effectKey, boolean created) {
        Map<String, Object> detail() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("contact_id", contactId);
            out.put("external_ref", request.externalRef());
            out.put("name", request.name());
            out.put("email", request.email());
            out.put("label", request.label());
            out.put("effect_key", effectKey);
            out.put("created", created);
            return out;
        }

        Map<String, Object> outcome() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("contact_id", contactId);
            out.put("external_ref", request.externalRef());
            out.put("created", created);
            out.put("effect_key", effectKey);
            return out;
        }

        /** A null key matches everything (unscoped lookup or delete). */
        boolean writtenBy(String key) {
            return key == null || key.equals(effectKey);
        }
    }

    private final MockControls controls;
    private final Map<String, Contact> contacts = new ConcurrentSkipListMap<>();
    private final AtomicLong seq = new AtomicLong();

    public CrmService(MockControls controls) {
        this.controls = controls;
    }

    /** REST create: always a new record. */
    public Map<String, Object> create(ContactRequest request) {
        controls.enter(UPSERT_ROUTE);
        validate(request);
        Contact contact = new Contact(nextId(), request, null, true);
        contacts.put(contact.contactId(), contact);
        return Map.of("contact_id", contact.contactId());
    }

    /**
     * MCP upsert. Replaying the same {@code effectKey} returns the original outcome without another write; a
     * different key updates the existing record for the ref (reported as {@code created=false}) or creates one.
     */
    public synchronized Map<String, Object> upsert(ContactRequest request, String effectKey) {
        controls.enter(UPSERT_ROUTE);
        validate(request);
        if (effectKey != null) {
            Optional<Contact> replay = byExternalRef(request.externalRef())
                    .filter(c -> effectKey.equals(c.effectKey()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get().outcome();
            }
        }
        Contact written = byExternalRef(request.externalRef()).findFirst()
                .map(c -> new Contact(c.contactId(), request, effectKey, false))
                .orElseGet(() -> new Contact(nextId(), request, effectKey, true));
        contacts.put(written.contactId(), written);
        return written.outcome();
    }

    /** @param effectKey when non-null, only a contact written by that effect counts as found */
    public Map<String, Object> findByExternalRef(String externalRef, String effectKey) {
        controls.enter(GET_ROUTE);
        List<Map<String, Object>> found = byExternalRef(externalRef)
                .filter(c -> c.writtenBy(effectKey))
                .map(Contact::detail)
                .toList();
        return Map.of("contacts", found);
    }

    public Map<String, Object> delete(String contactId) {
        controls.enter(DELETE_ROUTE);
        if (contacts.remove(contactId) == null) {
            throw new MockHttpException(404, "contact_not_found");
        }
        return Map.of("deleted", true);
    }

    /**
     * MCP {@code crm.delete} (crm.upsert's inverse): removes the contact with the ref; with an {@code effectKey}
     * only a contact that effect wrote. Repeating it is a no-op.
     */
    public Map<String, Object> deleteByExternalRef(String externalRef, String effectKey) {
        controls.enter(DELETE_ROUTE);
        List<String> ids = byExternalRef(externalRef)
                .filter(c -> c.writtenBy(effectKey))
                .map(Contact::contactId)
                .toList();
        long removed = ids.stream().filter(id -> contacts.remove(id) != null).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("external_ref", externalRef);
        out.put("deleted", removed > 0);
        out.put("deleted_count", removed);
        return out;
    }

    @Override
    public void reset() {
        contacts.clear();
        seq.set(0);
    }

    private Stream<Contact> byExternalRef(String externalRef) {
        return contacts.values().stream().filter(c -> c.request().externalRef().equals(externalRef));
    }

    private String nextId() {
        return "ct_%06d".formatted(seq.incrementAndGet());
    }

    private static void validate(ContactRequest request) {
        if (isBlank(request.externalRef()) || isBlank(request.name()) || isBlank(request.email())) {
            throw new MockHttpException(400, "invalid_request");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
