import { SelectionModel } from '@angular/cdk/collections';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatTableDataSource } from '@angular/material/table';
import { Title } from '@angular/platform-browser';
import { Instance, ObjectInfo, StorageClient } from '@yamcs/client';
import { AuthService } from '../../core/services/AuthService';
import { YamcsService } from '../../core/services/YamcsService';
import { CreateLayoutDialog } from './CreateLayoutDialog';
import { RenameLayoutDialog } from './RenameLayoutDialog';

@Component({
  templateUrl: './LayoutsPage.html',
  styleUrls: ['./LayoutsPage.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LayoutsPage {

  instance: Instance;

  displayedColumns = ['select', 'name', 'visibility', 'modified', 'actions'];
  dataSource = new MatTableDataSource<ObjectInfo>([]);
  selection = new SelectionModel<ObjectInfo>(true, []);

  private storageClient: StorageClient;

  constructor(
    title: Title,
    yamcs: YamcsService,
    private authService: AuthService,
    private dialog: MatDialog,
  ) {
    title.setTitle('Layouts');
    this.instance = yamcs.getInstance();
    this.storageClient = yamcs.createStorageClient();
    this.loadLayouts();
  }

  private loadLayouts() {
    const username = this.authService.getUser()!.getName();
    this.storageClient.listObjects('_global', `user.${username}`, {
      prefix: 'layouts',
    }).then(response => {
      this.dataSource.data = response.objects || [];
    });
  }

  isAllSelected() {
    const numSelected = this.selection.selected.length;
    const numRows = this.dataSource.data.length;
    return numSelected === numRows;
  }

  masterToggle() {
    this.isAllSelected() ?
        this.selection.clear() :
        this.dataSource.data.forEach(row => this.selection.select(row));
  }

  toggleOne(row: ObjectInfo) {
    if (!this.selection.isSelected(row) || this.selection.selected.length > 1) {
      this.selection.clear();
    }
    this.selection.toggle(row);
  }

  createLayout() {
    this.dialog.open(CreateLayoutDialog, {
      width: '400px',
    });
  }

  deleteSelectedLayouts() {
    const username = this.authService.getUser()!.getName();
    const bucket = `user.${username}`;
    if (confirm('Are you sure you want to delete the selected layouts?')) {
      const deletePromises = [];
      for (const object of this.selection.selected) {
        const promise = this.storageClient.deleteObject('_global', bucket, object.name);
        deletePromises.push(promise);
      }

      Promise.all(deletePromises).then(() => {
        this.loadLayouts();
      });
    }
  }

  renameLayout(layout: ObjectInfo) {
    const username = this.authService.getUser()!.getName();
    const dialogRef = this.dialog.open(RenameLayoutDialog, {
      width: '400px',
      data: {
        bucket: `user.${username}`,
        name: layout.name,
      }
    });
    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.loadLayouts();
      }
    });
  }
}
