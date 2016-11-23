/*
This file is part of SMAP.

SMAP is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
uSMAP is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with SMAP.  If not, see <http://www.gnu.org/licenses/>.

*/

/*
 * Functions for manipulating a question in the editor
 */

"use strict";

define([
         'jquery',
         'modernizr',
         'localise',
         'globals',
         'd3',
         'localise'], 
		function($, modernizr, lang, globals, d3, localise) {

	
	return {	
		add: add,
		redraw: redraw
	};
	


	/*
	 * Add
	 */
	function add(chartId, chart, config, data, width, height, margin) {

		var barWidth;   
	    
	    //config.x = d3.scaleBand().rangeRound([0, width]).padding(0.1);
	    config.x0 = d3.scaleBand().rangeRound([0, width], .1);
	    config.x1 = d3.scaleBand();
	    
		config.x0.domain(data.map(function(d) { 
			return d.period; 
			}));
		config.x1.domain(chart.groupLabels).rangeRound([0, config.x0.bandwidth()]);
		barWidth = config.x1.bandwidth();
	    
	    config.y = d3.scaleLinear().rangeRound([height, 0]);
	    config.y.domain([0, d3.max(data, function(d) { return d[chart.groups[0].label]; })]);
		
		config.xAxis = d3.axisBottom(config.x);
		config.yAxis = d3.axisLeft(config.y).ticks(10, "");
		
		config.g = config.svg.append("g")
	    	.attr("transform", "translate(" + margin.left + "," + margin.top + ")");

		config.g.append("g")
			.attr("class", "axis axis--x")
		    .attr("transform", "translate(0," + height + ")")
		    .call(config.xAxis)
		    .selectAll("text")	
            	.style("text-anchor", "end")
            	.attr("dx", "-.8em")
            	.attr("dy", ".15em")
            	.attr("transform", function(d) {
            		return "rotate(-65)" 
                	});
	
		// Add x-axis label
		var text = config.svg.append("text")             
	    		.attr("x", width / 2 )
	    		.attr("y",  height + margin.top + 40 )
	    		.style("text-anchor", "middle");
		
		if(chart.tSeries) {
			text.text(localise.set["c_" + chart.period]);
			
			// Max 10 X axis ticks
			if(data.length > 10) {
				var skips = Math.ceil(data.length / 10);
				var tick_text = config.svg.selectAll(".axis--x .tick text");
	
				tick_text.attr("class", function(d,i){
					if(i%skips != 0) d3.select(this).remove();
				});
			}
			
		} else if(chart.select_name) {
			text.text(chart.select_name);
		} else {
			text.text(chart.humanName);
		}
		
		// Add y-axis label
		config.svg.append("text")
        	.attr("text-anchor", "middle")  
        	.attr("transform", "translate("+ (margin.left/3) +","+(height/2)+")rotate(-90)")  
        	.text(localise.set[chart.fn]);
		
		config.g.append("g")
		    .attr("class", "axis axis--y")
		    .call(config.yAxis);
	
		
	}
	
	/*
	 * Update a bar chart
	 */
	function redraw(chartId, chart, config, data, width, height, margin) {
		
		var barWidth;
		
		/*
		config.x0.domain(data.map(function(d) { 
			if(!d.key || d.key === "") {
				return localise.set["c_undef"]; 
			} else {
				return d.key;
			}
		}));
		*/

	    config.x0 = d3.scaleBand().rangeRound([0, width], .1);
	    config.x1 = d3.scaleBand();
	    
		config.x0.domain(data.map(function(d) { 
			return d.period; 
			}));
		config.x1.domain(chart.groupLabels).rangeRound([0, config.x0.bandwidth()]);
		barWidth = config.x1.bandwidth();

		config.y.domain([0, d3.max(data, function(d) { return d[chart.groups[0].label]; })]);
		
		// Update axes
		config.svg.select(".axis--y")
			//.transition()
			//.duration(500)
			.call(config.yAxis.ticks(5, ""));
		config.svg.select(".axis--x")
			//.transition()
			//.duration(500)
			.call(config.xAxis);
		
		var period = config.svg.selectAll(".period").data(data)
	    	.enter().append("g")
	        	.attr("class", "period")
	        	.attr("transform", function(d) { return "translate(" + x0(d.key) + ",0)"; });
		var bars = period.selectAll(".bar").data(data, function(d) { return d.columnNames; });
		
		
		// New bars
		bars.enter()
			.append("rect")
			.attr("class", "bar")
			.attr("x", function(d) { 
				if(!d.key || d.key === "") {
					return config.x(localise.set["c_undef"]);
				} else {
					return config.x(d.key); 
				}
			})
	    	.attr("y", function(d) { 
	    		return config.y(d[chart.groups[0].label]); 
	    		})
	    	.attr("width", barWidth)
	    	.attr("height", function(d) { return height - config.y(d[chart.groups[0].label]); });
		
		// Bars being update
		bars.transition()
			.duration(300)
	    	.attr("x", function(d) { return config.x(d.key); })
	    	.attr("y", function(d) { return config.y(d[chart.groups[0].label]); })
	    	.attr("width", barWidth)
	    	.attr("height", function(d) { return height - config.y(d[chart.groups[0].label]); });
			
		// Bars being removed
		bars.exit()
			.transition()
			.duration(300)
			.attr("y", config.y(0))
			.attr("height", height - config.y(0))
			//.style('fill-opacity', 1e-6)
			.remove();
	}
	

});