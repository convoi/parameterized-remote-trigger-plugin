package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

import hudson.FilePath;
import hudson.model.AbstractBuild;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ParameterFileLoader {
    private final RemoteBuildConfiguration remoteBuildConfiguration;

    public ParameterFileLoader(RemoteBuildConfiguration remoteBuildConfiguration) {
        this.remoteBuildConfiguration = remoteBuildConfiguration;
    }

    /**
     * Reads a file from the jobs workspace, and loads the list of parameters from with in it. It will also call
     * ```getCleanedParameters``` before returning.
     *
     * @param build
     * @return List<String> of build parameters
     */
    List<String> loadExternalParameterFile(AbstractBuild<?, ?> build, String parameterFile) {

        FilePath workspace = build.getWorkspace();
        BufferedReader br = null;
        List<String> parameterList = new ArrayList<String>();
        try {

            String filePath = workspace + parameterFile;
            String sCurrentLine;

            br = new BufferedReader(new FileReader(filePath));

            while ((sCurrentLine = br.readLine()) != null) {
                parameterList.add(sCurrentLine);
            }


        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return parameterList;
    }
}