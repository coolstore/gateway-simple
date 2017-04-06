/**
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.coolstore.api_gateway;

import com.redhat.coolstore.api_gateway.model.Inventory;
import com.redhat.coolstore.api_gateway.model.Product;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.component.jackson.ListJacksonDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.processor.aggregate.AggregationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ProductGateway extends RouteBuilder {
	private static final Logger LOG = LoggerFactory.getLogger(ProductGateway.class);

	@Autowired
	private Environment env;
	
    @Override
    public void configure() throws Exception {
    	try {
    		getContext().setTracing(Boolean.parseBoolean(env.getProperty("ENABLE_TRACER", "false")));	
		} catch (Exception e) {
			LOG.error("Failed to parse the ENABLE_TRACER value: {}", env.getProperty("ENABLE_TRACER", "false"));
		}

        JacksonDataFormat productFormatter = new ListJacksonDataFormat();
        productFormatter.setUnmarshalType(Product.class);

		restConfiguration()
				.contextPath("/services").apiContextPath("/services-docs")
				.apiProperty("host", "")
				.apiProperty("api.title", "CoolStore Gateway API")
				.apiProperty("api.version", "1.0")
				.component("servlet")
				.bindingMode(RestBindingMode.json);

		rest("/products").description("Access the CoolStore products and their availability")
            .produces(MediaType.APPLICATION_JSON_VALUE)

        .get("/").description("Retrieves the product catalog, including inventory availability").outType(Product.class)
            .route().id("productRoute")
                	.setBody(simple("null"))
                	.removeHeaders("CamelHttp*")
                	.recipientList(simple("http4://{{env:CATALOG_ENDPOINT:catalog:8080}}/api/catalog")).end()
                	.unmarshal(productFormatter)
	                .split(body()).parallelProcessing()
	                .enrich("direct:inventory", new InventoryEnricher())
	            .end()
	            
        .endRest();

        from("direct:inventory")
            .id("inventoryRoute")
            .setHeader("itemId", simple("${body.itemId}"))            
			.setBody(simple("null"))
			.removeHeaders("CamelHttp*")
			.recipientList(simple("http4://{{env:INVENTORY_ENDPOINT:inventory:8080}}/api/inventory/${header.itemId}")).end()
            .setHeader("CamelJacksonUnmarshalType", simple(Inventory.class.getName()))
            .unmarshal().json(JsonLibrary.Jackson, Inventory.class);


        
    }

    private class InventoryEnricher implements AggregationStrategy {
        @Override
        public Exchange aggregate(Exchange original, Exchange resource) {

            // Add the discovered availability to the product and set it back
            Product p = original.getIn().getBody(Product.class);
            Inventory i = resource.getIn().getBody(Inventory.class);
            p.setAvailability(i);
            original.getOut().setBody(p);
            log.info("------------------->"+p);
            
            return original;

        }
    }
}
