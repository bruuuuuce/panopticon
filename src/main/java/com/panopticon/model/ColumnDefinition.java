package com.panopticon.model;

/**
 * Metadata for a single column in a {@link DataResult}. {@code type} is a
 * loose, provider-defined label (a JDBC type name for the jdbc provider,
 * something simpler like "string"/"number" for others) — it's descriptive
 * metadata for the frontend, not a contract any provider must conform to.
 */
public record ColumnDefinition(String name, String type) {
}
