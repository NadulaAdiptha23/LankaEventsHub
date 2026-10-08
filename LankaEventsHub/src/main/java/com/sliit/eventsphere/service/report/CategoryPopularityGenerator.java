package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.repository.EventRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Backs CATEGORY_POPULARITY - the one report type with a breakdown table
 * (rows) rather than just headline figures, and (like PlatformOverviewGenerator)
 * a current-state snapshot with no date window to apply.
 */
@Component
public class CategoryPopularityGenerator extends AbstractReportGenerator {

    private final EventRepository eventRepository;

    public CategoryPopularityGenerator(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    protected ReportFigures calculate(ReportContext context) {
        List<ReportResultResponse.Row> rows = new ArrayList<>();
        long total = 0;
        for (Object[] row : eventRepository.countGroupedByCategory()) {
            String category = (String) row[0];
            long count = ((Number) row[1]).longValue();
            total += count;
            rows.add(new ReportResultResponse.Row(
                    category == null ? "Uncategorised" : category,
                    String.valueOf(count), "events"));
        }

        return new ReportFigures(List.of(
                ReportResultResponse.Figure.of("Categories in use", rows.size(),
                        "built from the data, not a fixed list"),
                ReportResultResponse.Figure.of("Events counted", total, "across every category")
        ), "Events by category", rows);
    }
}
