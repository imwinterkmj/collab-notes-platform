package com.collabnotes.platform.note;

import java.util.List;

public record NotePageResponse(List<NoteSummaryResponse> items, int page, int size, boolean hasNext) {
    public NotePageResponse { items = List.copyOf(items); }

    @Override public String toString() {
        return "NotePageResponse[page=" + page + ", size=" + size + ", count=" + items.size()
                + ", hasNext=" + hasNext + ", redacted]";
    }
}
