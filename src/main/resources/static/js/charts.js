/**
 * ECharts option builders for the panel types that render as charts.
 * Colors are read from the theme's CSS custom properties (theme.css) rather
 * than duplicated here, so the palette stays defined in one place.
 */
const Charts = (() => {
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

    function grid() {
        return { left: 6, right: 16, top: 12, bottom: 6, containLabel: true };
    }

    function barOption(rows, xField, yField, seriesName) {
        const categories = rows.map((r) => String(r[xField]));
        const values = rows.map((r) => Number(r[yField]));
        return {
            backgroundColor: 'transparent',
            grid: grid(),
            tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...tooltipStyle },
            xAxis: { type: 'category', data: categories, ...axisStyle() },
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
            grid: grid(),
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

    return { barOption, lineOption, donutOption };
})();
