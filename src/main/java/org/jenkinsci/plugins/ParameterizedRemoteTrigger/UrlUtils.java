package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class UrlUtils {
    /**
     * Helper function for character encoding
     *
     * @param dirtyValue
     * @return encoded value
     */
    public static String encodeValue(String dirtyValue) {
        String cleanValue = "";

        try {
            cleanValue = URLEncoder.encode(dirtyValue, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return cleanValue;
    }
}