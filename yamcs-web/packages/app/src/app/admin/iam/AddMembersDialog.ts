import { SelectionModel } from '@angular/cdk/collections';
import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, Inject, ViewChild } from '@angular/core';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatPaginator } from '@angular/material/paginator';
import { MatTableDataSource } from '@angular/material/table';
import { UserInfo } from '@yamcs/client';
import { fromEvent } from 'rxjs';
import { debounceTime, distinctUntilChanged, map } from 'rxjs/operators';
import { YamcsService } from '../../core/services/YamcsService';

export interface MemberItem {
  label: string;
  user?: UserInfo;
}

@Component({
  selector: 'app-add-members-dialog',
  templateUrl: './AddMembersDialog.html',
  styleUrls: ['./AddMembersDialog.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddMembersDialog implements AfterViewInit {

  displayedColumns = [
    'select',
    'type',
    'name',
  ];

  @ViewChild('filter', { static: true })
  filter: ElementRef;

  @ViewChild(MatPaginator, { static: true })
  paginator: MatPaginator;

  dataSource = new MatTableDataSource<MemberItem>();
  selection = new SelectionModel<MemberItem>(true, []);

  constructor(
    private dialogRef: MatDialogRef<AddMembersDialog>,
    yamcs: YamcsService,
    @Inject(MAT_DIALOG_DATA) readonly data: any
  ) {
    const existingItems: MemberItem[] = data.items;
    const existingUsernames = existingItems.filter(i => i.user).map(i => i.user!.name);
    yamcs.yamcsClient.getUsers().then(users => {
      const items = (users || []).filter(user => existingUsernames.indexOf(user.name) === -1).map(user => {
        return {
          label: user.displayName || user.name,
          user,
        };
      });
      this.dataSource.data = items;
    });
  }

  ngAfterViewInit() {
    this.dataSource.filterPredicate = (member, filter) => {
      return member.label.toLowerCase().indexOf(filter) >= 0;
    };
    this.dataSource.paginator = this.paginator;

    fromEvent(this.filter.nativeElement, 'keyup').pipe(
      debounceTime(150), // Keep low -- Client-side filter
      map(() => this.filter.nativeElement.value.trim()), // Detect 'distinct' on value not on KeyEvent
      distinctUntilChanged(),
    ).subscribe(value => {
      this.dataSource.filter = value.toLowerCase();
    });
  }

  toggleOne(row: MemberItem) {
    if (!this.selection.isSelected(row) || this.selection.selected.length > 1) {
      this.selection.clear();
    }
    this.selection.toggle(row);
  }

  save() {
    this.dialogRef.close(this.selection.selected);
  }
}
