/*
 * Copyright 2018 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.test.manualmode.management.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.core.testrunner.ServerControl;
import org.wildfly.core.testrunner.UnsuccessfulOperationException;
import org.wildfly.core.testrunner.WildflyTestRunner;

@RunWith(WildflyTestRunner.class)
@ServerControl(manual = true)
public class GitRepositoryTestCase extends AbstractGitRepositoryTestCase {
    private Repository repository;


    @After
    public void after() throws Exception {
        if (container.isStarted()) {
            try {
                removeDeployment();
            } catch (Exception sde) {
                // ignore error undeploying, might not exist
            }
            removeSystemProperty();
            container.stop();
        }
        closeRepository();
        closeEmptyRemoteRepository();
    }

    private void closeRepository() throws Exception{
        if (repository != null) {
            repository.close();
        }
        if (Files.exists(getDotGitDir())) {
            FileUtils.delete(getDotGitDir().toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
        }
        if(Files.exists(getDotGitIgnore())) {
            FileUtils.delete(getDotGitIgnore().toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
        }
    }

    /**
     * Start server (no parameter)
     * git repository shouldn't be initialized
     */
    @Test
    public void startDefaultTest() throws Exception {
        container.start();
        Assert.assertTrue(Files.notExists(getDotGitDir()));
        Assert.assertTrue(Files.notExists(getDotGitIgnore()));
    }

    /**
     * Start server with parameter --git-repo=local
     */
    @Test
    public void startGitRepoLocal() throws Exception {
        container.startGitBackedConfiguration("local", null, null);
        Assert.assertTrue("Directory not found " + getDotGitDir(), Files.exists(getDotGitDir()));
        Assert.assertTrue("File not found " + getDotGitIgnore(), Files.exists(getDotGitIgnore()));

        repository = createRepository();
        int expectedNumberOfCommits = 1;

        // start => initial commit
        List<String> commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Repository initialized", commits.get(0));
        List<String> paths = listFilesInCommit(repository);

        // change configuration => commit
        addSystemProperty();
        expectedNumberOfCommits++;
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));
        paths = listFilesInCommit(repository);
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals("configuration/standalone.xml", paths.get(0));

        // deploy deployment => commit
        deployEmptyDeployment();
        listUntracked(repository).forEach(l-> System.out.println(l));
        expectedNumberOfCommits++;
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));
        paths = listFilesInCommit(repository);
        Assert.assertEquals(Arrays.toString(paths.toArray()), 2, paths.size());
        Assert.assertEquals("configuration/standalone.xml", paths.get(0));
        String contentPath = paths.get(1);
        Assert.assertTrue(contentPath.startsWith("data/content/") && contentPath.endsWith("/content"));

        // undeploy deployment (/deployment=name:undeploy) => commited
        undeployDeployment();
        expectedNumberOfCommits++;
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));
        paths = listFilesInCommit(repository);
        Assert.assertEquals(Arrays.toString(paths.toArray()), 1, paths.size());
        Assert.assertEquals("configuration/standalone.xml", paths.get(0));

        // exploded deployment => commited
        explodeDeployment();
        expectedNumberOfCommits++;
        commits = listCommits(repository);
        Assert.assertEquals(Arrays.toString(commits.toArray()), expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));
        paths = listFilesInCommit(repository);
        Assert.assertEquals(Arrays.toString(paths.toArray()), 3, paths.size());
        Assert.assertEquals("-" + contentPath, paths.get(0));
        Assert.assertEquals("configuration/standalone.xml", paths.get(1));
        String contentFile = paths.get(2);
        Assert.assertNotEquals(contentPath, contentFile);
        Assert.assertTrue(contentFile.startsWith("data/content/") && contentFile.endsWith("/content/file"));

        // exploded deployment - add content => commited
        addContentToDeployment();
        expectedNumberOfCommits++;
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));
        paths = listFilesInCommit(repository);
        Assert.assertEquals(Arrays.toString(paths.toArray()), 4, paths.size());
        Assert.assertEquals("configuration/standalone.xml", paths.get(1));
        Assert.assertEquals("-" + contentFile, paths.get(0));
        contentFile = paths.get(2);
        String contentProperties = paths.get(3);
        Assert.assertNotEquals(contentPath, contentFile);
        Assert.assertNotEquals(contentPath, contentProperties);
        Assert.assertTrue(contentFile.startsWith("data/content/") && contentFile.endsWith("/content/file"));
        Assert.assertTrue(contentProperties.startsWith("data/content/") && contentProperties.endsWith("/content/test.properties"));

        // exploded deployment - remove content => commited
        removeContentFromDeployment();
        expectedNumberOfCommits++;
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));
        paths = listFilesInCommit(repository);
        Assert.assertEquals(Arrays.toString(paths.toArray()), 4, paths.size());
        Assert.assertEquals("-" + contentFile, paths.get(0));
        Assert.assertEquals("-" + contentProperties, paths.get(1));
        Assert.assertEquals("configuration/standalone.xml", paths.get(2));
        contentFile = paths.get(3);
        Assert.assertTrue(contentFile.startsWith("data/content/") && contentFile.endsWith("/content/file"));

        // :clean-obsolete-content
        // remove deployment => commit
        removeDeployment();
        expectedNumberOfCommits++;
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));
        paths = listFilesInCommit(repository);
        Assert.assertEquals(Arrays.toString(paths.toArray()), 2, paths.size());
        Assert.assertEquals( "-" + contentFile, paths.get(0));
        Assert.assertEquals("configuration/standalone.xml", paths.get(1));
        // :clean-obsolete-content
        // deployment-overlay

        // there are no tags
        List<String> tags = listTags(repository);
        Assert.assertEquals(0, tags.size());

        // :take-snapshot => tag = timestamp
        takeSnapshot(null, null);
        tags = listTags(repository);
        Assert.assertEquals(1, tags.size());
        verifyDefaultSnapshotString(tags.get(0));
        // this snapshot is not expected to have commit, as there is no uncommited remove of content data
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("Storing configuration", commits.get(0));

        // :take-snapshot(name=foo) => success, tag=foo
        takeSnapshot("foo", null);
        tags = listTags(repository);
        Assert.assertEquals(2, tags.size());
        // there should be two tags, from this and previous snapshot
        Assert.assertEquals("foo", tags.get(1));
        // this should be the same commit as with previous snapshot
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());

        // :take-snapshot(name=foo) => fail, tag already exists
        try {
            takeSnapshot("foo", null);
            Assert.fail("Operation should have failed");
        } catch (UnsuccessfulOperationException uoe) {
            // good
            Assert.assertEquals("\"WFLYCTL0455: Can't take snapshot foo because it already exists\"", uoe.getMessage());
        }

        // :take-snapshot(description=bar) => tag = timestamp, commit msg=bar
        takeSnapshot(null, "bar");
        expectedNumberOfCommits++;
        tags = listTags(repository);
        Assert.assertEquals(3, tags.size());
        // tags are ordered alphabetically, so we want second with default name
        verifyDefaultSnapshotString(tags.get(1));
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("bar", commits.get(0));

        // :take-snapshot(name=fooo, description=barbar) => success, tag=fooo, commit msg=bar
        takeSnapshot("fooo", "bar");
        expectedNumberOfCommits++;
        tags = listTags(repository);
        Assert.assertEquals(4, tags.size());
        // fooo is alphabetically last
        Assert.assertEquals("fooo", tags.get(3));
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("bar", commits.get(0));

        // :take-snapshot(name=fooo, description=bar) => fail
        try {
            takeSnapshot("fooo", "bar");
            Assert.fail("Operation should have failed");
        } catch (UnsuccessfulOperationException uoe) {
            // good
            Assert.assertEquals("\"WFLYCTL0455: Can't take snapshot fooo because it already exists\"", uoe.getMessage());
        }

        // :publish-configuration => push to origin
        publish(null);
        commits = listCommits(repository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("bar", commits.get(0));

        // :publish-configuration(location=empty) => push to empty)
        publish("empty");
        tags = listTags(emptyRemoteRepository);
        Assert.assertEquals(4, tags.size());
        Assert.assertEquals("fooo", tags.get(3));
        commits = listCommits(emptyRemoteRepository);
        Assert.assertEquals(expectedNumberOfCommits, commits.size());
        Assert.assertEquals("bar", commits.get(0));
    }

     protected List<String> listUntracked(Repository repository) throws IOException, GitAPIException {
         List<String> result = new ArrayList<>();
         result.add("Untracked:");
        try (Git git = new Git(repository)) {
            Status status = git.status().call();
            result.addAll(status.getUntrackedFolders());
            result.addAll(status.getUntracked());
            return result;
        }
    }
}
