/**
 * ECharts option builders for the panel types that render as charts.
 * Colors are read from the theme's CSS custom properties (theme.css) rather
 * than duplicated here, so the palette stays defined in one place.
 */
const root = getComputedStyle(document.documentElement);
const cssVar = (name) => root.getPropertyValue(name).trim();

const colors = {
    text: cssVar('--text-secondary'),
    muted: cssVar('--text-muted'),
    grid: cssVar('--gridline'),
    axis: cssVar('--axis'),
    surface: cssVar('--surface'),
    surfaceRaised: cssVar('--surface-raised'),
    border: cssVar('--border'),
    series1: cssVar('--series-1'),
    categorical: [
        cssVar('--series-1'), cssVar('--series-2'), cssVar('--series-3'), cssVar('--series-4'),
        cssVar('--series-5'), cssVar('--series-6'), cssVar('--series-7'), cssVar('--series-8'),
    ],
};

const tooltipStyle = {
    backgroundColor: colors.surfaceRaised,
    borderColor: colors.border,
    borderWidth: 1,
    textStyle: { color: colors.text, fontSize: 12 },
};

function axisStyle() {
    return {
        axisLine: { lineStyle: { color: colors.axis } },
        axisTick: { show: false },
        axisLabel: { color: colors.muted, fontSize: 11 },
        splitLine: { lineStyle: { color: colors.grid } },
    };
}

/**
 * Category axis labels for bar/line charts. ECharts silently drops labels
 * it thinks won't fit ("interval: auto") rather than shrinking or wrapping
 * them — at a narrow panel width that reads as missing data, not a layout
 * choice. `interval: 0` forces every category to render; a steep-ish fixed
 * rotation keeps them from colliding once a panel gets tight, without
 * needing to measure the panel's actual pixel width up front. The steeper
 * the angle, the less each label reaches sideways into its neighbor (or, for
 * the leftmost bar, past the chart's own edge) — 20° was too shallow for a
 * label as long as "BANK_TRANSFER" and got clipped; 40° keeps the same
 * category comfortably inside the panel.
 */
function categoryAxisStyle() {
    const style = axisStyle();
    style.axisLabel = { ...style.axisLabel, interval: 0, rotate: 40 };
    return style;
}

function chartGrid() {
    return { left: 6, right: 16, top: 12, bottom: 6, containLabel: true };
}

/**
 * Bar categories are rotated (see categoryAxisStyle) so long labels like
 * "BANK_TRANSFER" don't get silently dropped — but a rotated label on the
 * leftmost bar leans further left than an unrotated one, and containLabel's
 * auto-sizing doesn't always give it enough room before the chart's own
 * edge. A wider explicit left margin gives it somewhere to lean into.
 */
function barGrid() {
    return { left: 36, right: 16, top: 12, bottom: 6, containLabel: true };
}

function barOption(rows, xField, yField, seriesName) {
    const categories = rows.map((r) => String(r[xField]));
    const values = rows.map((r) => Number(r[yField]));
    return {
        backgroundColor: 'transparent',
        grid: barGrid(),
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...tooltipStyle },
        // Bar categories are few and each one is identity, not a sample of a
        // continuum — every label must show (see categoryAxisStyle), unlike
        // the line chart's dense date axis below where eliding some ticks is fine.
        xAxis: { type: 'category', data: categories, ...categoryAxisStyle() },
        yAxis: { type: 'value', ...axisStyle(), splitNumber: 4 },
        series: [{
            name: seriesName || yField,
            type: 'bar',
            data: values,
            barMaxWidth: 24,
            itemStyle: { color: colors.series1, borderRadius: [4, 4, 0, 0] },
        }],
    };
}

function lineOption(rows, xField, yField, seriesName) {
    const categories = rows.map((r) => String(r[xField]));
    const values = rows.map((r) => Number(r[yField]));
    return {
        backgroundColor: 'transparent',
        grid: chartGrid(),
        tooltip: { trigger: 'axis', ...tooltipStyle },
        xAxis: { type: 'category', data: categories, boundaryGap: false, ...axisStyle() },
        yAxis: { type: 'value', ...axisStyle(), splitNumber: 4 },
        series: [{
            name: seriesName || yField,
            type: 'line',
            data: values,
            smooth: false,
            symbol: 'circle',
            symbolSize: 8,
            lineStyle: { width: 2, color: colors.series1 },
            itemStyle: { color: colors.series1, borderColor: colors.surface, borderWidth: 2 },
            areaStyle: { color: colors.series1, opacity: 0.1 },
        }],
    };
}

function donutOption(rows, labelField, valueField) {
    const data = rows.map((r) => ({ name: String(r[labelField]), value: Number(r[valueField]) }));
    return {
        backgroundColor: 'transparent',
        color: colors.categorical,
        tooltip: { trigger: 'item', ...tooltipStyle },
        legend: {
            orient: 'vertical',
            right: 4,
            top: 'middle',
            itemWidth: 10,
            itemHeight: 10,
            textStyle: { color: colors.text, fontSize: 11 },
        },
        series: [{
            type: 'pie',
            radius: ['55%', '78%'],
            center: ['42%', '50%'],
            // The legend already carries identity for these few categories, and slice
            // labels at this radius collide with it (and each other) once a name is
            // more than a couple characters wide — tooltip carries the exact value instead.
            label: { show: false },
            labelLine: { show: false },
            itemStyle: { borderColor: colors.surface, borderWidth: 2 },
            data,
        }],
    };
}

export const Charts = { barOption, lineOption, donutOption };
