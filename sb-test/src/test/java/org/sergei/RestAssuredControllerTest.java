package org.sergei;

import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;

public class RestAssuredControllerTest {
    public static void main(String[] args) {
        Response response =
        given()
            .param("name", "John")
        .when()
            .get("/greeting")
        .then()
            .statusCode(200)
            .contentType(JSON)
            //.body("id", equalTo(6))
            .body("content", equalTo("Hello, John!"))
        .extract()
                .response();

        String greeting = response.path("content");
        System.out.println(greeting);

        /*JsonPath jsonPath = new JsonPath(json).setRoot("lotto");
        int lottoId = jsonPath.getInt("lottoId");*/
        response.body().prettyPrint();
    }
}
