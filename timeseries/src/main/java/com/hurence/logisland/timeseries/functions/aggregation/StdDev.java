/**
 * Copyright (C) 2016 Hurence (support@hurence.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hurence.logisland.timeseries.functions.aggregation;

import com.hurence.logisland.timeseries.MetricTimeSeries;
import com.hurence.logisland.timeseries.functions.ChronixAggregation;
import com.hurence.logisland.timeseries.functions.FunctionValueMap;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 * The standard deviation analysis
 *
 * @author f.lautenschlager
 */
public final class StdDev implements ChronixAggregation<MetricTimeSeries> {
    /**
     * Calculates the standard deviation of the first time series.
     *
     * @param timeSeries the time series
     * @return the percentile or 0 if the list is empty
     */
    @Override
    public void execute(MetricTimeSeries timeSeries, FunctionValueMap functionValueMap) {
        //If it is empty, we return NaN
        if (timeSeries.size() <= 0) {
            functionValueMap.add(this, Double.NaN);
            return;
        }
        //Else calculate the analysis value
        functionValueMap.add(this, com.hurence.logisland.timeseries.functions.math.StdDev.dev(timeSeries.getValues()));
    }

    @Override
    public String getQueryName() {
        return "dev";
    }

    @Override
    public String getTimeSeriesType() {
        return "metric";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        return new EqualsBuilder()
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .toHashCode();
    }
}
