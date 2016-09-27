package org.sergei;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.InputStream;

/**
 * Created by Sergei_Doroshenko on 8/17/2016.
 */
public class HttpServiceTest {
    public static void main (String[] args)
    {
        HttpClient httpClient = HttpClientBuilder.create().build(); //Use this instead

        try {
            HttpPost request = new HttpPost("http://localhost:8080/greeting");
            StringEntity payload = new StringEntity(
                    "{\"checkAccess\":" +
                            "{" +
                            "\"gateName\":\"authenticate\"," +
                            "\"additionalData\": {\"username\": \"chuck_norris\", \"password\":\"Welcome123\" }" +
                            "}" +
                            "}");
            request.addHeader("content-type", "application/json");
            request.setEntity(payload);
            HttpResponse response = httpClient.execute(request);

            InputStream content = response.getEntity().getContent();
            byte[] buffer = new byte[1024];

            while ( content.read(buffer) > 0 ) {
                System.out.println(new String(buffer));
            }
            // handle response here...
        }catch (Exception ex) {
            // handle exception here
        }
    }
}
