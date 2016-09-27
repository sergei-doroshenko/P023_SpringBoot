package org.sergei;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by Sergei_Doroshenko on 8/17/2016.
 */
public class HttpServiceTestGet {
    public static void main (String[] args)
    {
        HttpClient httpClient = HttpClientBuilder.create().build(); //Use this instead
        InputStream content = null;
        try {
            HttpGet request = new HttpGet("http://localhost:8080/greeting?name=Test");
            HttpResponse response = httpClient.execute(request);

            // handle response here...
            content = response.getEntity().getContent();
            byte[] buffer = new byte[1024];

            while ( content.read(buffer) > 0 ) {
                System.out.println(new String(buffer));
            }

        }catch (Exception ex) {
            // handle exception here
            ex.printStackTrace();
        } finally {
            if (content != null) {
                try {
                    content.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
