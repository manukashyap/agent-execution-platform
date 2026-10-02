package com.conversive.aep.mocks.crm;

import com.conversive.aep.mocks.admin.MockControls;
import com.conversive.aep.mocks.admin.Resettable;
import com.conversive.aep.mocks.support.MockHttpException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/** Non-idempotent CRM: every upsert creates a new record, duplicates are the point. */
@Service
public class CrmService implements Resettable {

    public static final String UPSERT_ROUTE = "crm.upsert";
    public static final String GET_ROUTE = "crm.get";
    public static final String DELETE_ROUTE = "crm.delete";

    public record ContactRequest(@JsonProperty("external_ref") String externalRef, String name, String email,
                                 String label) {
    }

    private record Contact(String contactId, ContactRequest request) {
        Map<String, Object> detail() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("contact_id", contactId);
            out.put("external_ref", request.externalRef());
            out.put("name", request.name());
            out.put("email", request.email());
            out.put("label", request.label());
            return out;
        }
    }

    private final MockControls controls;
    private final Map<String, Contact> contacts = new ConcurrentSkipListMap<>();
    private final AtomicLong seq = new AtomicLong();

    public CrmService(MockControls controls) {
        this.controls = controls;
    }

    public Map<String, Object> create(ContactRequest request) {
        controls.enter(UPSERT_ROUTE);
        if (isBlank(request.externalRef()) || isBlank(request.name()) || isBlank(request.email())) {
            throw new MockHttpException(400, "invalid_request");
        }
        Contact contact = new Contact("ct_%06d".formatted(seq.incrementAndGet()), request);
        contacts.put(contact.contactId(), contact);
        return Map.of("contact_id", contact.contactId());
    }

    public Map<String, Object> findByExternalRef(String externalRef) {
        controls.enter(GET_ROUTE);
        List<Map<String, Object>> found = contacts.values().stream()
                .filter(c -> c.request().externalRef().equals(externalRef))
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

    /** MCP {@code crm.delete} (crm.upsert's inverse): removes every contact with the ref; repeating it is a no-op. */
    public Map<String, Object> deleteByExternalRef(String externalRef) {
        controls.enter(DELETE_ROUTE);
        List<String> ids = contacts.values().stream()
                .filter(c -> c.request().externalRef().equals(externalRef))
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
