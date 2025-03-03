import { ChangeDetectionStrategy, Component, Inject, ViewChild } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { DownloadEventsOptions, GetEventsOptions } from '@yamcs/client';
import { BehaviorSubject } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { rowAnimation } from '../../animations';
import { AppConfig, APP_CONFIG, ExtraColumnInfo } from '../../core/config/AppConfig';
import { AuthService } from '../../core/services/AuthService';
import { PreferenceStore } from '../../core/services/PreferenceStore';
import { Synchronizer } from '../../core/services/Synchronizer';
import { YamcsService } from '../../core/services/YamcsService';
import { ColumnInfo } from '../../shared/template/ColumnChooser';
import { Option, Select } from '../../shared/template/Select';
import { subtractDuration } from '../../shared/utils';
import { CreateEventDialog } from './CreateEventDialog';
import { EventsDataSource } from './EventsDataSource';


const defaultInterval = 'PT1H';

@Component({
  templateUrl: './EventsPage.html',
  styleUrls: ['./EventsPage.css'],
  animations: [rowAnimation],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventsPage {

  @ViewChild('intervalSelect', { static: false })
  intervalSelect: Select;

  validStart: Date | null;
  validStop: Date | null;

  // Same as filter.interval but only updates after 'apply' in case of custom dates
  // This allows showing visual indicators for the visible data set before a custom
  // range is actually applied.
  appliedInterval: string;

  filterForm = new FormGroup({
    filter: new FormControl(),
    severity: new FormControl('INFO'),
    source: new FormControl('ANY'),
    interval: new FormControl(defaultInterval),
    customStart: new FormControl(null, [
      Validators.pattern(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/),
    ]),
    customStop: new FormControl(null, [
      Validators.pattern(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/),
    ]),
  });

  dataSource: EventsDataSource;

  columns: ColumnInfo[] = [
    { id: 'severity', label: 'Severity' },
    { id: 'gentime', label: 'Generation Time', alwaysVisible: true },
    { id: 'message', label: 'Message', alwaysVisible: true },
    { id: 'type', label: 'Type' },
    { id: 'source', label: 'Source' },
    { id: 'rectime', label: 'Reception Time' },
    { id: 'seqNumber', label: 'Sequence Number' },
  ];

  /**
   * Columns specific to a site
   */
  extraColumns: ExtraColumnInfo[] = [];

  displayedColumns = [
    'severity',
    'gentime',
    'message',
    'type',
    'source',
  ];

  severityOptions: Option[] = [
    { id: 'INFO', label: 'Info level', selected: true },
    { id: 'WATCH', label: 'Watch level' },
    { id: 'WARNING', label: 'Warning level' },
    { id: 'DISTRESS', label: 'Distress level' },
    { id: 'CRITICAL', label: 'Critical level' },
    { id: 'SEVERE', label: 'Severe level' },
  ];

  sourceOptions$ = new BehaviorSubject<Option[]>([
    { id: 'ANY', label: 'Any source', selected: true },
  ]);

  intervalOptions: Option[] = [
    { id: 'PT1H', label: 'Last hour', selected: true },
    { id: 'PT6H', label: 'Last 6 hours' },
    { id: 'P1D', label: 'Last 24 hours' },
    { id: 'NO_LIMIT', label: 'No limit' },
    { id: 'CUSTOM', label: 'Custom', group: true },
  ];

  downloadURL$ = new BehaviorSubject<string | null>(null);

  // Would prefer to use formGroup, but when using valueChanges this
  // only is updated after the callback...
  private severity = 'INFO';
  private source: string;
  private filter: string;

  constructor(
    private yamcs: YamcsService,
    private authService: AuthService,
    private preferenceStore: PreferenceStore,
    private dialog: MatDialog,
    @Inject(APP_CONFIG) appConfig: AppConfig,
    private router: Router,
    private route: ActivatedRoute,
    title: Title,
    synchronizer: Synchronizer,
  ) {
    title.setTitle('Events');

    // Consider site-specific configuration
    if (appConfig.events) {
      const eventConfig = appConfig.events;
      this.extraColumns = eventConfig.extraColumns || [];
      for (const extraColumn of this.extraColumns) {
        for (let i = 0; i < this.columns.length; i++) {
          if (this.columns[i].id === extraColumn.after) {
            this.columns.splice(i + 1, 0, extraColumn);
            break;
          }
        }
      }
      if (eventConfig.displayedColumns) {
        this.displayedColumns = eventConfig.displayedColumns;
      }
    }

    const cols = preferenceStore.getVisibleColumns('events').filter(el => {
      // Filter out extraColumns (maybe from another instance - we should maybe store this per instance)
      for (const column of this.columns) {
        if (column.id === el) {
          return true;
        }
      }
    });
    if (cols.length) {
      this.displayedColumns = cols;
    }

    yamcs.getInstanceClient()!.getEventSources().then(sources => {
      for (const source of sources) {
        this.sourceOptions$.next([
          ...this.sourceOptions$.value,
          {
            id: source,
            label: source,
            selected: this.filterForm.get('source')!.value === source,
          }]);
      }
    });

    this.dataSource = new EventsDataSource(yamcs, synchronizer);

    this.initializeOptions();
    this.loadData();

    this.filterForm.get('filter')!.valueChanges.pipe(
      debounceTime(400),
    ).forEach(filter => {
      this.filter = filter;
      this.loadData();
    });

    this.filterForm.get('severity')!.valueChanges.forEach(severity => {
      this.severity = severity;
      this.loadData();
    });

    this.filterForm.get('source')!.valueChanges.forEach(source => {
      this.source = (source !== 'ANY') ? source : null;
      this.loadData();
    });

    this.filterForm.get('interval')!.valueChanges.forEach(nextInterval => {
      if (nextInterval === 'CUSTOM') {
        const customStart = this.validStart || new Date();
        const customStop = this.validStop || new Date();
        this.filterForm.get('customStart')!.setValue(customStart.toISOString());
        this.filterForm.get('customStop')!.setValue(customStop.toISOString());
      } else if (nextInterval === 'NO_LIMIT') {
        this.validStart = null;
        this.validStop = null;
        this.appliedInterval = nextInterval;
        this.loadData();
      } else {
        this.validStop = yamcs.getMissionTime();
        this.validStart = subtractDuration(this.validStop, nextInterval);
        this.appliedInterval = nextInterval;
        this.loadData();
      }
    });
  }

  private initializeOptions() {
    const queryParams = this.route.snapshot.queryParamMap;
    if (queryParams.has('filter')) {
      this.filter = queryParams.get('filter') || '';
      this.filterForm.get('filter')!.setValue(this.filter);
    }
    if (queryParams.has('severity')) {
      this.severity = queryParams.get('severity')!;
      for (const option of this.severityOptions) {
        option.selected = (option.id === this.severity);
      }
      this.filterForm.get('severity')!.setValue(this.severity);
    }
    if (queryParams.has('source')) {
      this.source = queryParams.get('source')!;
      for (const option of this.sourceOptions$.value) {
        option.selected = (option.id === this.source);
      }
      this.filterForm.get('source')!.setValue(this.source);
    }
    if (queryParams.has('interval')) {
      this.appliedInterval = queryParams.get('interval')!;
      for (const option of this.intervalOptions) {
        option.selected = (option.id === this.appliedInterval);
      }
      this.filterForm.get('interval')!.setValue(this.appliedInterval);
      if (this.appliedInterval === 'CUSTOM') {
        const customStart = queryParams.get('customStart')!;
        this.filterForm.get('customStart')!.setValue(customStart);
        this.validStart = new Date(customStart);
        const customStop = queryParams.get('customStop')!;
        this.filterForm.get('customStop')!.setValue(customStop);
        this.validStop = new Date(customStop);
      } else if (this.appliedInterval === 'NO_LIMIT') {
        this.validStart = null;
        this.validStop = null;
      } else {
        this.validStop = this.yamcs.getMissionTime();
        this.validStart = subtractDuration(this.validStop, this.appliedInterval);
      }
    } else {
      this.appliedInterval = defaultInterval;
      this.validStop = this.yamcs.getMissionTime();
      this.validStart = subtractDuration(this.validStop, defaultInterval);
    }
  }

  jumpToNow() {
    const interval = this.filterForm.value['interval'];
    if (interval === 'NO_LIMIT') {
      // NO_LIMIT may include future data under erratic conditions. Reverting
      // to the default interval is more in line with the wording 'jump to now'.
      this.intervalSelect.select(defaultInterval);
    } else if (interval === 'CUSTOM') {
      // For simplicity reasons, just reset to default 1h interval.
      this.intervalSelect.select(defaultInterval);
    } else {
      this.validStop = this.yamcs.getMissionTime();
      this.validStart = subtractDuration(this.validStop, interval);
      this.loadData();
    }
  }

  startStreaming() {
    this.filterForm.get('interval')!.setValue('NO_LIMIT');
    this.dataSource.startStreaming();
  }

  stopStreaming() {
    this.dataSource.stopStreaming();
  }

  applyCustomDates() {
    this.validStart = new Date(this.filterForm.value['customStart']);
    this.validStop = new Date(this.filterForm.value['customStop']);
    this.appliedInterval = 'CUSTOM';
    this.loadData();
  }

  /**
   * Loads the first page of data within validStart and validStop
   */
  loadData() {
    this.updateURL();
    const options: GetEventsOptions = {
      severity: this.severity as any,
    };
    if (this.validStart) {
      options.start = this.validStart.toISOString();
    }
    if (this.validStop) {
      options.stop = this.validStop.toISOString();
    }
    if (this.filter) {
      options.q = this.filter;
    }
    if (this.source) {
      options.source = this.source;
    }

    const dlOptions: DownloadEventsOptions = {
      severity: this.severity as any,
    };
    if (this.validStart) {
      dlOptions.start = this.validStart.toISOString();
    }
    if (this.validStop) {
      dlOptions.stop = this.validStop.toISOString();
    }
    if (this.filter) {
      dlOptions.q = this.filter;
    }
    if (this.source) {
      dlOptions.source = this.source;
    }

    const instanceClient = this.yamcs.getInstanceClient()!;
    this.dataSource.loadEvents(options).then(events => {
      const downloadURL = instanceClient.getEventsDownloadURL(dlOptions);
      this.downloadURL$.next(downloadURL);
    });
  }

  loadMoreData() {
    const options: GetEventsOptions = {
      severity: this.severity as any,
    };
    if (this.validStart) {
      options.start = this.validStart.toISOString();
    }
    if (this.filter) {
      options.q = this.filter;
    }
    if (this.source) {
      options.source = this.source;
    }

    this.dataSource.loadMoreData(options);
  }

  private updateURL() {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        filter: this.filter || null,
        severity: this.severity,
        source: this.source || null,
        interval: this.appliedInterval,
        customStart: this.appliedInterval === 'CUSTOM' ? this.filterForm.value['customStart'] : null,
        customStop: this.appliedInterval === 'CUSTOM' ? this.filterForm.value['customStop'] : null,
      },
      queryParamsHandling: 'merge',
    });
  }

  updateColumns(displayedColumns: string[]) {
    this.displayedColumns = displayedColumns;
    this.preferenceStore.setVisibleColumns('events', displayedColumns);
  }

  updateSeverity(severity: string) {
    this.filterForm.get('severity')!.setValue(severity);
  }

  updateSource(source: string) {
    this.filterForm.get('source')!.setValue(source);
  }

  updateInterval(interval: string) {
    this.filterForm.get('interval')!.setValue(interval);
  }

  mayWriteEvents() {
    return this.authService.getUser()!.hasSystemPrivilege('WriteEvents');
  }

  createEvent() {
    const dialogInstance = this.dialog.open(CreateEventDialog, {
      width: '400px',
    });
    dialogInstance.afterClosed().subscribe(result => {
      if (result) {
        this.jumpToNow();
      }
    });
  }
}
