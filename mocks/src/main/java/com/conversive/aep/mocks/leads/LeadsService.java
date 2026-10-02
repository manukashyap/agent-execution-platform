package com.conversive.aep.mocks.leads;

import com.conversive.aep.mocks.admin.MockControls;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** Read-only, deterministic lead source. */
@Service
public class LeadsService {

    public static final String ROUTE = "leads.fetch";
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 200;

    private static final List<String> FIRST = List.of("Ada", "Grace", "Alan", "Edsger", "Barbara", "Linus", "Margaret",
            "Dennis");
    private static final List<String> LAST = List.of("Lovelace", "Hopper", "Turing", "Dijkstra", "Liskov", "Torvalds",
            "Hamilton", "Ritchie", "Knuth");
    private static final List<String> COMPANY = List.of("Acme", "Globex", "Initech", "Umbrella", "Hooli", "Stark",
            "Wayne");

    private final MockControls controls;

    public LeadsService(MockControls controls) {
        this.controls = controls;
    }

    public Map<String, Object> fetch(Integer limit) {
        controls.enter(ROUTE);
        int n = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        return Map.of("leads", IntStream.range(0, n).mapToObj(LeadsService::lead).toList());
    }

    private static Map<String, Object> lead(int i) {
        String first = FIRST.get(i % FIRST.size());
        String last = LAST.get(i % LAST.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "lead-%04d".formatted(i + 1));
        out.put("name", first + " " + last);
        out.put("email", "%s.%s.%d@example.test".formatted(first, last, i + 1).toLowerCase());
        out.put("company", COMPANY.get(i % COMPANY.size()) + " Corp");
        return out;
    }
}
