// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.notedb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_LABEL;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_PATCH_SET;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.GERRIT_PLACEHOLDER_HOST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gerrit.server.git.VersionedMetaData;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.util.LabelVote;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

/**
 * A single delta to apply atomically to a change.
 * <p>
 * This delta becomes a single commit on the notes branch, so there are
 * limitations on the set of modifications that can be handled in a single
 * update. In particular, there is a single author and timestamp for each
 * update.
 * <p>
 * This class is not thread-safe.
 */
public class ChangeUpdate extends VersionedMetaData {
  public interface Factory {
    ChangeUpdate create(Change change);
    ChangeUpdate create(Change change, Date when);
    ChangeUpdate create(Change change, Date when, IdentifiedUser user);
  }

  private final NotesMigration migration;
  private final GitRepositoryManager repoManager;
  private final AccountCache accountCache;
  private final MetaDataUpdate.User updateFactory;
  private final LabelTypes labelTypes;
  private final Change change;
  private final IdentifiedUser user;
  private final Date when;
  private final TimeZone tz;
  private final Map<String, Short> approvals;
  private final Map<Account.Id, ReviewerState> reviewers;
  private String subject;
  private PatchSet.Id psId;

  @AssistedInject
  ChangeUpdate(
      @GerritPersonIdent PersonIdent serverIdent,
      GitRepositoryManager repoManager,
      NotesMigration migration,
      AccountCache accountCache,
      MetaDataUpdate.User updateFactory,
      ProjectCache projectCache,
      IdentifiedUser user,
      @Assisted Change change) {
    this(serverIdent, repoManager, migration, accountCache, updateFactory,
        projectCache, user, change, serverIdent.getWhen());
  }

  @AssistedInject
  ChangeUpdate(
      @GerritPersonIdent PersonIdent serverIdent,
      GitRepositoryManager repoManager,
      NotesMigration migration,
      AccountCache accountCache,
      MetaDataUpdate.User updateFactory,
      ProjectCache projectCache,
      IdentifiedUser user,
      @Assisted Change change,
      @Assisted Date when) {
    this(serverIdent, repoManager, migration, accountCache, updateFactory,
        projectCache, change, when, user);
  }

  @AssistedInject
  ChangeUpdate(
      @GerritPersonIdent PersonIdent serverIdent,
      GitRepositoryManager repoManager,
      NotesMigration migration,
      AccountCache accountCache,
      MetaDataUpdate.User updateFactory,
      ProjectCache projectCache,
      @Assisted Change change,
      @Assisted Date when,
      @Assisted IdentifiedUser user) {
    this(serverIdent, repoManager, migration, accountCache, updateFactory,
        projectCache.get(change.getDest().getParentKey()).getLabelTypes(),
        change, when, user);
  }

  @VisibleForTesting
  ChangeUpdate(
      PersonIdent serverIdent,
      GitRepositoryManager repoManager,
      NotesMigration migration,
      AccountCache accountCache,
      MetaDataUpdate.User updateFactory,
      LabelTypes labelTypes,
      Change change,
      Date when,
      IdentifiedUser user) {
    this.repoManager = repoManager;
    this.migration = migration;
    this.accountCache = accountCache;
    this.updateFactory = updateFactory;
    this.labelTypes = labelTypes;
    this.change = change;
    this.user = user;
    this.when = when;
    this.tz = serverIdent.getTimeZone();
    this.approvals = Maps.newTreeMap(labelTypes.nameComparator());
    this.reviewers = Maps.newLinkedHashMap();
  }

  public Change getChange() {
    return change;
  }

  public IdentifiedUser getUser() {
    return user;
  }

  public Date getWhen() {
    return when;
  }

  public void putApproval(String label, short value) {
    approvals.put(label, value);
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public void setPatchSetId(PatchSet.Id psId) {
    checkArgument(psId == null || psId.getParentKey().equals(change.getKey()));
    this.psId = psId;
  }

  public void putReviewer(Account.Id reviewer, ReviewerState type) {
    checkArgument(type != ReviewerState.REMOVED, "invalid ReviewerType");
    reviewers.put(reviewer, type);
  }

  public void removeReviewer(Account.Id reviewer) {
    reviewers.put(reviewer, ReviewerState.REMOVED);
  }

  public RevCommit commit() throws IOException {
    return commit(checkNotNull(updateFactory, "MetaDataUpdate.Factory")
        .create(change.getProject(), user));
  }

  @Override
  public RevCommit commit(MetaDataUpdate md) throws IOException {
    if (!migration.write()) {
      return null;
    }
    Repository repo = repoManager.openRepository(change.getProject());
    try {
      load(repo);
    } catch (ConfigInvalidException e) {
      throw new IOException(e);
    } finally {
      repo.close();
    }

    md.setAllowEmpty(true);
    CommitBuilder cb = md.getCommitBuilder();
    cb.setCommitter(newCommitter());
    return super.commit(md);
  }

  public PersonIdent newIdent(Account author) {
    return new PersonIdent(
        author.getFullName(),
        author.getId().get() + "@" + GERRIT_PLACEHOLDER_HOST,
        when, tz);
  }

  public PersonIdent newCommitter() {
    return newIdent(user.getAccount());
  }

  @Override
  public BatchMetaDataUpdate openUpdate(MetaDataUpdate update) throws IOException {
    if (migration.write()) {
      return super.openUpdate(update);
    }
    return new BatchMetaDataUpdate() {
      @Override
      public void write(CommitBuilder commit) {
        // Do nothing.
      }

      @Override
      public void write(VersionedMetaData config, CommitBuilder commit) {
        // Do nothing.
      }

      @Override
      public RevCommit createRef(String refName) {
        return null;
      }

      @Override
      public RevCommit commit() {
        return null;
      }

      @Override
      public RevCommit commitAt(ObjectId revision) {
        return null;
      }

      @Override
      public void close() {
        // Do nothing.
      }
    };
  }

  @Override
  protected String getRefName() {
    return ChangeNoteUtil.changeRefName(change.getId());
  }

  @Override
  protected void onSave(CommitBuilder commit) {
    if (approvals.isEmpty() && reviewers.isEmpty()) {
      return;
    }
    int ps = psId != null ? psId.get() : change.currentPatchSetId().get();
    StringBuilder msg = new StringBuilder();
    if (subject != null) {
      msg.append(subject);
    } else {
      msg.append("Update patch set ").append(ps);
    }
    msg.append("\n\n");
    addFooter(msg, FOOTER_PATCH_SET, ps);
    for (Map.Entry<Account.Id, ReviewerState> e : reviewers.entrySet()) {
      Account account = accountCache.get(e.getKey()).getAccount();
      PersonIdent ident = newIdent(account);
      addFooter(msg, e.getValue().getFooterKey())
          .append(ident.getName())
          .append(" <").append(ident.getEmailAddress()).append(">\n");
    }
    for (Map.Entry<String, Short> e : approvals.entrySet()) {
      LabelType lt = labelTypes.byLabel(e.getKey());
      if (lt != null) {
        addFooter(msg, FOOTER_LABEL,
            new LabelVote(lt.getName(), e.getValue()).formatWithEquals());
      }
    }
    commit.setMessage(msg.toString());
  }

  private static StringBuilder addFooter(StringBuilder sb, FooterKey footer) {
    return sb.append(footer.getName()).append(": ");
  }

  private static void addFooter(StringBuilder sb, FooterKey footer,
      Object value) {
    addFooter(sb, footer).append(value).append('\n');
  }

  @Override
  protected void onLoad() throws IOException, ConfigInvalidException {
    // Do nothing; just reads current revision.
  }
}