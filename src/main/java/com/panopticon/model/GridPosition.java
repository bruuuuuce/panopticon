package com.panopticon.model;

/**
 * Position and span of a panel within a dashboard's CSS grid.
 * Rows/columns are 1-based, matching CSS Grid line numbering.
 */
public record GridPosition(int row, int col, int rowSpan, int colSpan) {
}
